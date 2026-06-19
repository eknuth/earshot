package dev.eknuth.earshot

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.extensions.OrtxPackage
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * Provider seam for native ASR runtimes the published library does not bundle.
 *
 * Whisper runs in-process on the ONNX Runtime dependency the library already declares.
 * The NVIDIA models run through sherpa-onnx, which ships its own ONNX Runtime `.so` and
 * Kotlin API; bundling that into the published artifact would clash with the existing
 * `onnxruntime-android` native lib. So the host app provides the sherpa implementation
 * and registers it here, mirroring how iOS registers its WhisperKit provider.
 */
interface NativeAsrProvider {
    /**
     * Load the model whose files live under [modelDir] (encoder/decoder/joiner + tokens
     * for a transducer). Returns true once the recognizer is ready.
     */
    suspend fun loadModel(modelDir: String): Boolean

    /** Transcribe a 16kHz mono WAV at [audioPath]; throws on failure. */
    suspend fun transcribe(audioPath: String): String

    /** True once the recognizer is loaded and ready. */
    fun isReady(): Boolean

    /** Release the native recognizer. */
    fun release()
}

/**
 * Settable singleton holder for the [NativeAsrProvider]. The host app sets
 * [implementation] once (see the sample app's sherpa wiring) when it intends to run a
 * sherpa-backed model.
 */
object NativeAsrProviderHolder {
    var implementation: NativeAsrProvider? = null
}

/**
 * Android implementation of TranscriptionEngine using Microsoft Olive-generated Whisper model.
 *
 * This implementation uses a single combined ONNX model that handles:
 * - Audio preprocessing (mel spectrogram)
 * - Encoder
 * - Decoder with beam search
 * - Token decoding to text
 *
 * Input: Raw PCM audio at 16kHz
 * Output: Transcribed text directly
 *
 * Model source: https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/whisper/local/android
 *
 * For non-Whisper models (NVIDIA Parakeet / Nemotron, [AsrBackend.SHERPA_TRANSDUCER]
 * and [AsrBackend.SHERPA_STREAMING]) the engine delegates to an app-registered
 * [NativeAsrProvider]. sherpa-onnx ships its own ONNX Runtime and Kotlin API, so it is
 * supplied by the host app rather than bundled in the published library, the same way
 * the iOS WhisperKit runtime is. This keeps the published artifact free of a second,
 * conflicting `libonnxruntime.so`.
 */
actual class TranscriptionEngine {

    companion object {
        private const val TAG = "TranscriptionEngine"
        private const val SAMPLE_RATE = 16000
        private const val MAX_LENGTH = 200  // Max tokens to generate
        private const val CHUNK_SAMPLES = 480000  // 30 seconds at 16kHz - required by Olive model
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var session: OrtSession? = null

    private var modelPath: String? = null
    private var config: TranscriptionConfig = TranscriptionConfig()
    private val mutex = Mutex()

    // Which backend the configured model maps to. Resolved in initialize(); defaults to
    // the Whisper Olive path so an unknown/unset model name behaves as it always did.
    private var backend: AsrBackend = AsrBackend.WHISPER_OLIVE

    private val provider: NativeAsrProvider?
        get() = NativeAsrProviderHolder.implementation

    @Volatile
    private var modelStatus: ModelStatus = ModelStatus.NotDownloaded

    @Volatile
    private var isInitialized: Boolean = false

    actual suspend fun initialize(config: TranscriptionConfig): Boolean = mutex.withLock {
        this.config = config
        this.backend = Models.backendFor(config.modelName)

        // Non-Whisper models run through the app-registered sherpa provider.
        if (backend != AsrBackend.WHISPER_OLIVE) {
            val p = provider
            if (p == null) {
                Log.e(TAG, "No NativeAsrProvider registered for backend $backend")
                modelStatus = ModelStatus.Error("No sherpa provider registered")
                isInitialized = false
                return@withLock false
            }
            val dir = modelPath
            if (dir == null || !File(dir).exists()) {
                Log.e(TAG, "Model path not set or doesn't exist: $dir")
                modelStatus = ModelStatus.NotDownloaded
                return@withLock false
            }
            return@withLock try {
                val ok = p.loadModel(dir)
                isInitialized = ok
                modelStatus = if (ok) ModelStatus.Ready else ModelStatus.Error("Provider failed to load model")
                ok
            } catch (e: Throwable) {
                Log.e(TAG, "Sherpa provider failed to load model", e)
                modelStatus = ModelStatus.Error("Failed to load: ${e.message}")
                isInitialized = false
                false
            }
        }

        return@withLock withContext(Dispatchers.IO) {
            try {
                val path = modelPath
                if (path == null || !File(path).exists()) {
                    Log.e(TAG, "Model path not set or doesn't exist: $path")
                    modelStatus = ModelStatus.NotDownloaded
                    return@withContext false
                }

                val modelFile = File(path, "model.onnx")
                if (!modelFile.exists()) {
                    Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                    return@withContext false
                }

                Log.d(TAG, "Initializing Whisper Olive model: ${modelFile.absolutePath}")

                // Initialize ONNX Runtime
                ortEnvironment = OrtEnvironment.getEnvironment()
                val env = ortEnvironment!!

                val sessionOptions = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(4)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    // On-device memory tuning. ONNX Runtime's default CPU arena pre-allocates and
                    // pools large blocks for speed; on a phone that pool dominates peak memory. We
                    // turn the arena off and disable memory-pattern pre-planning so transient
                    // tensors are freed between runs. Peak footprint drops sharply for a small,
                    // bounded latency cost, which is the right trade on hardware the user owns.
                    setCPUArenaAllocator(false)
                    setMemoryPatternOptimization(false)
                    // Register ONNX Runtime Extensions for custom operators (BpeDecoder, etc.)
                    registerCustomOpLibrary(OrtxPackage.getLibraryPath())
                }

                Log.d(TAG, "Creating ONNX session...")
                val startTime = System.currentTimeMillis()
                session = env.createSession(modelFile.absolutePath, sessionOptions)
                Log.d(TAG, "Session created in ${System.currentTimeMillis() - startTime}ms")

                // Log model inputs/outputs for debugging
                session?.let { sess ->
                    Log.d(TAG, "Model inputs:")
                    for ((name, info) in sess.inputInfo) {
                        Log.d(TAG, "  $name: ${info.info}")
                    }
                    Log.d(TAG, "Model outputs:")
                    for ((name, info) in sess.outputInfo) {
                        Log.d(TAG, "  $name: ${info.info}")
                    }
                }

                isInitialized = true
                modelStatus = ModelStatus.Ready

                Log.d(TAG, "Whisper transcription engine initialized successfully")
                true

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize transcription engine", e)
                modelStatus = ModelStatus.Error("Failed to initialize: ${e.message}")
                isInitialized = false
                false
            }
        }
    }

    actual suspend fun transcribe(audioPath: String): TranscriptionEngineResult {
        if (backend != AsrBackend.WHISPER_OLIVE) {
            val p = provider
                ?: return TranscriptionEngineResult.Error("No sherpa provider registered")
            val start = System.currentTimeMillis()
            return try {
                val text = p.transcribe(audioPath)
                TranscriptionEngineResult.Success(
                    text = text,
                    language = config.language,
                    confidence = null,
                    processingTimeMs = System.currentTimeMillis() - start
                )
            } catch (e: Throwable) {
                TranscriptionEngineResult.Error("Transcription failed: ${e.message}", e)
            }
        }
        return transcribeWhisper(audioPath)
    }

    private suspend fun transcribeWhisper(audioPath: String): TranscriptionEngineResult =
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()

            val sess = session
            val env = ortEnvironment

            if (!isInitialized || sess == null || env == null) {
                return@withContext TranscriptionEngineResult.Error(
                    "Engine not initialized. Call initialize() first."
                )
            }

            try {
                // 1. Load audio from WAV file
                val audioData = loadWavAudio(audioPath)
                    ?: return@withContext TranscriptionEngineResult.Error("Failed to load audio file")

                Log.d(TAG, "Loaded audio: ${audioData.size} samples (${audioData.size / SAMPLE_RATE}s)")

                // Limit audio length if configured
                val maxSamples = config.maxAudioLengthSeconds * SAMPLE_RATE
                val trimmedAudio = if (audioData.size > maxSamples) {
                    Log.d(TAG, "Trimming audio from ${audioData.size} to $maxSamples samples")
                    audioData.copyOfRange(0, maxSamples)
                } else {
                    audioData
                }

                // Process audio in 30-second chunks (Olive model requirement)
                val allText = StringBuilder()
                val numChunks = (trimmedAudio.size + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES
                Log.d(TAG, "Processing $numChunks chunk(s) of 30s each")

                for (chunkIdx in 0 until numChunks) {
                    val startSample = chunkIdx * CHUNK_SAMPLES
                    val endSample = minOf(startSample + CHUNK_SAMPLES, trimmedAudio.size)

                    // Extract chunk and pad to exactly 30 seconds if needed
                    val chunkAudio = FloatArray(CHUNK_SAMPLES)
                    trimmedAudio.copyInto(chunkAudio, 0, startSample, endSample)
                    // Remaining samples are already 0 (silence padding)

                    Log.d(TAG, "Processing chunk ${chunkIdx + 1}/$numChunks (${endSample - startSample} samples, padded to $CHUNK_SAMPLES)")

                    // 2. Create input tensors
                    // Audio PCM tensor: [1, num_samples] - must be exactly 30s
                    val audioShape = longArrayOf(1, CHUNK_SAMPLES.toLong())
                    val audioTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(chunkAudio), audioShape)

                    // Configuration tensors
                    val minLengthTensor = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                    val maxLengthTensor = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(MAX_LENGTH)), longArrayOf(1))
                    val numBeamsTensor = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                    val numReturnSeqTensor = OnnxTensor.createTensor(env, IntBuffer.wrap(intArrayOf(1)), longArrayOf(1))
                    val lengthPenaltyTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf(1))
                    val repetitionPenaltyTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf(1))

                    try {
                        val inputs = mapOf(
                            "audio_pcm" to audioTensor,
                            "min_length" to minLengthTensor,
                            "max_length" to maxLengthTensor,
                            "num_beams" to numBeamsTensor,
                            "num_return_sequences" to numReturnSeqTensor,
                            "length_penalty" to lengthPenaltyTensor,
                            "repetition_penalty" to repetitionPenaltyTensor
                        )

                        Log.d(TAG, "Running inference on chunk ${chunkIdx + 1}...")
                        val inferenceStart = System.currentTimeMillis()
                        val results = sess.run(inputs)
                        val inferenceTime = System.currentTimeMillis() - inferenceStart
                        Log.d(TAG, "Chunk ${chunkIdx + 1} inference completed in ${inferenceTime}ms")

                        // 3. Extract text from output
                        // Output is Array<Array<String>> at [0][0]
                        val output = results[0].value
                        val chunkText = when (output) {
                            is Array<*> -> {
                                val first = output[0]
                                when (first) {
                                    is Array<*> -> (first[0] as? String) ?: ""
                                    is String -> first
                                    else -> {
                                        Log.e(TAG, "Unexpected output type: ${first?.javaClass}")
                                        ""
                                    }
                                }
                            }
                            is String -> output
                            else -> {
                                Log.e(TAG, "Unexpected output type: ${output.javaClass}")
                                ""
                            }
                        }

                        results.close()

                        if (chunkText.isNotBlank()) {
                            if (allText.isNotEmpty()) allText.append(" ")
                            allText.append(chunkText.trim())
                        }
                        Log.d(TAG, "Chunk ${chunkIdx + 1} text: \"$chunkText\"")

                    } finally {
                        audioTensor.close()
                        minLengthTensor.close()
                        maxLengthTensor.close()
                        numBeamsTensor.close()
                        numReturnSeqTensor.close()
                        lengthPenaltyTensor.close()
                        repetitionPenaltyTensor.close()
                    }
                } // end chunk loop

                val processingTime = System.currentTimeMillis() - startTime
                val finalText = allText.toString().trim()
                Log.d(TAG, "Transcription completed in ${processingTime}ms: \"$finalText\"")

                TranscriptionEngineResult.Success(
                    text = finalText,
                    language = "en",
                    confidence = null,
                    processingTimeMs = processingTime
                )

            } catch (e: Exception) {
                Log.e(TAG, "Transcription failed", e)
                TranscriptionEngineResult.Error(
                    message = "Transcription failed: ${e.message}",
                    cause = e
                )
            }
        }

    actual fun isReady(): Boolean =
        if (backend != AsrBackend.WHISPER_OLIVE) isInitialized && (provider?.isReady() ?: false)
        else isInitialized && session != null

    actual fun getModelStatus(): ModelStatus = modelStatus

    actual suspend fun ensureModelDownloaded(): Boolean = mutex.withLock {
        val path = modelPath
        if (path != null && File(path).exists() && modelFilePresent(path)) {
            modelStatus = ModelStatus.Ready
            return@withLock true
        }
        return@withLock false
    }

    actual fun release() {
        if (backend != AsrBackend.WHISPER_OLIVE) {
            provider?.release()
        }
        session?.close()
        session = null
        ortEnvironment?.close()
        ortEnvironment = null
        isInitialized = false
    }

    actual fun isAvailable(): Boolean = true

    /**
     * Set the model directory path. Called by ModelDownloader after download completes.
     */
    fun setModelPath(path: String) {
        modelPath = path
        if (File(path).exists() && modelFilePresent(path)) {
            modelStatus = ModelStatus.Ready
        }
    }

    /**
     * Whether the expected primary model file is staged under [dir]. The Whisper Olive
     * path needs `model.onnx`; a sherpa transducer needs its encoder. setModelPath runs
     * before a model is selected, so we accept either layout.
     */
    private fun modelFilePresent(dir: String): Boolean =
        File(dir, "model.onnx").exists() || File(dir, "encoder.int8.onnx").exists()

    /**
     * Update download progress. Called by ModelDownloader during download.
     */
    fun updateDownloadProgress(progress: Float) {
        modelStatus = ModelStatus.Downloading(progress)
    }

    // ========== Audio Loading ==========

    private fun loadWavAudio(path: String): FloatArray? {
        return try {
            RandomAccessFile(File(path), "r").use { file ->
                // Read RIFF header
                val riff = ByteArray(4)
                file.read(riff)
                if (String(riff) != "RIFF") {
                    Log.e(TAG, "Invalid WAV file: not RIFF")
                    return null
                }

                file.skipBytes(4)  // File size

                val wave = ByteArray(4)
                file.read(wave)
                if (String(wave) != "WAVE") {
                    Log.e(TAG, "Invalid WAV file: not WAVE")
                    return null
                }

                // Find data chunk
                var sampleRate = 0
                var bitsPerSample = 0
                var numChannels = 0
                var dataSize = 0

                while (file.filePointer < file.length()) {
                    val chunkId = ByteArray(4)
                    file.read(chunkId)
                    val chunkSize = file.readIntLE()

                    when (String(chunkId)) {
                        "fmt " -> {
                            file.skipBytes(2)  // Audio format
                            numChannels = file.readShortLE()
                            sampleRate = file.readIntLE()
                            file.skipBytes(6)  // Byte rate, block align
                            bitsPerSample = file.readShortLE()
                            if (chunkSize > 16) file.skipBytes(chunkSize - 16)
                        }
                        "data" -> {
                            dataSize = chunkSize
                            break
                        }
                        else -> file.skipBytes(chunkSize)
                    }
                }

                if (dataSize == 0) {
                    Log.e(TAG, "No data chunk found in WAV")
                    return null
                }
                if (bitsPerSample != 16) {
                    Log.e(TAG, "Unsupported bits per sample: $bitsPerSample (expected 16)")
                    return null
                }

                Log.d(TAG, "WAV: ${sampleRate}Hz, ${numChannels}ch, ${bitsPerSample}bit, ${dataSize} bytes")

                // Read PCM data
                val numSamples = dataSize / (bitsPerSample / 8) / numChannels
                val pcmData = ByteArray(dataSize)
                file.read(pcmData)

                // Convert to float, mono if needed
                val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
                val floatData = FloatArray(numSamples)

                for (i in 0 until numSamples) {
                    var sample = 0f
                    for (c in 0 until numChannels) {
                        sample += buffer.short / 32768f
                    }
                    floatData[i] = sample / numChannels
                }

                floatData
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load WAV file", e)
            null
        }
    }

    private fun RandomAccessFile.readIntLE(): Int {
        val b = ByteArray(4)
        read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun RandomAccessFile.readShortLE(): Int {
        val b = ByteArray(2)
        read(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }
}
