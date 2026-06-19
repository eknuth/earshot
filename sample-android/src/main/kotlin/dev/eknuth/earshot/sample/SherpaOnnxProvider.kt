package dev.eknuth.earshot.sample

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import dev.eknuth.earshot.NativeAsrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * sherpa-onnx-backed [NativeAsrProvider] for the NVIDIA transducer models (Parakeet-TDT).
 *
 * This is the Android counterpart to the iOS WhisperKit provider: the published Earshot
 * library declares the [NativeAsrProvider] seam, and this sample app supplies the sherpa
 * runtime. sherpa-onnx does its own feature extraction and TDT/RNNT decode, so Whisper's
 * mel/tokenizer code is not on this path.
 *
 * Expects a model directory containing `encoder.int8.onnx`, `decoder.int8.onnx`,
 * `joiner.int8.onnx`, and `tokens.txt` (the layout of
 * sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8). The native libraries
 * `libsherpa-onnx-jni.so` + `libonnxruntime.so` must be present in jniLibs.
 */
class SherpaOnnxProvider(
    private val numThreads: Int = 4,
) : NativeAsrProvider {

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    override suspend fun loadModel(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        val encoder = File(modelDir, "encoder.int8.onnx")
        val decoder = File(modelDir, "decoder.int8.onnx")
        val joiner = File(modelDir, "joiner.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")
        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            return@withContext false
        }

        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                // No modelType: let sherpa auto-detect the NeMo TDT path from the encoder
                // metadata (model_type=EncDecRNNTBPEModel, vocab_size in the encoder). Forcing
                // "transducer" reads vocab_size from the decoder, which this model lacks.
            ),
            decodingMethod = "greedy_search",
        )
        recognizer = OfflineRecognizer(config = config)
        true
    }

    override suspend fun transcribe(audioPath: String): String = withContext(Dispatchers.Default) {
        val r = recognizer ?: error("sherpa recognizer not loaded")
        val wave = WaveReader.readWave(audioPath)
        val stream = r.createStream()
        stream.acceptWaveform(wave.samples, wave.sampleRate)
        r.decode(stream)
        val text = r.getResult(stream).text
        stream.release()
        text.trim()
    }

    override fun isReady(): Boolean = recognizer != null

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}
