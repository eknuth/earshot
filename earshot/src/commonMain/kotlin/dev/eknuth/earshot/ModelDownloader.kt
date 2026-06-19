package dev.eknuth.earshot

import kotlinx.coroutines.flow.Flow

/**
 * Result of a model download operation.
 */
sealed class ModelDownloadResult {
    data class Success(val modelPath: String) : ModelDownloadResult()
    data class Error(val message: String, val cause: Throwable? = null) : ModelDownloadResult()
}

/**
 * Progress update during model download.
 */
data class DownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progress: Float  // 0.0 to 1.0
)

/**
 * The on-device runtime a model is built for.
 *
 * Earshot started as a Whisper-only library; these tags let one [TranscriptionEngine]
 * dispatch to the right backend so newer model families (NVIDIA's transducer models,
 * run through sherpa-onnx) can be selected without changing the public API.
 *
 *  - [WHISPER_OLIVE]: Whisper exported as a single all-in-one ONNX graph (Microsoft
 *    Olive). Audio in, text out, run on ONNX Runtime + Extensions. This is the default.
 *  - [SHERPA_TRANSDUCER]: an offline FastConformer + transducer model (e.g. NVIDIA
 *    Parakeet-TDT) run through sherpa-onnx's `OfflineRecognizer`.
 *  - [SHERPA_STREAMING]: a cache-aware streaming FastConformer model (e.g. NVIDIA
 *    Nemotron Speech) run through sherpa-onnx's `OnlineRecognizer`.
 *
 * The two sherpa backends are supplied by the host app, not bundled in the published
 * library, the same way the iOS WhisperKit runtime is registered by the app. See
 * `NativeAsrProvider` and the sample apps.
 */
enum class AsrBackend {
    WHISPER_OLIVE,
    SHERPA_TRANSDUCER,
    SHERPA_STREAMING,
}

/**
 * Information about a single model file.
 */
data class ModelFileInfo(
    val filename: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * Model information with multiple files.
 */
data class ModelInfo(
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val downloadUrl: String,  // Primary file URL (for backward compatibility)
    val checksum: String?,
    val files: List<ModelFileInfo> = emptyList(),  // Additional files
    val backend: AsrBackend = AsrBackend.WHISPER_OLIVE,
)

/**
 * Platform-specific model downloader for transcription models.
 */
expect class ModelDownloader {
    /**
     * Check if the model is already downloaded.
     */
    suspend fun isModelDownloaded(modelName: String): Boolean

    /**
     * Get the path to the downloaded model.
     * Returns null if model is not downloaded.
     */
    fun getModelPath(modelName: String): String?

    /**
     * Download the model.
     * Emits progress updates during download.
     *
     * @param modelInfo Information about the model to download
     * @return Flow of progress updates, completing with the final result
     */
    fun downloadModel(modelInfo: ModelInfo): Flow<DownloadProgress>

    /**
     * Download the model and wait for completion.
     *
     * @param modelInfo Information about the model to download
     * @return Result of the download operation
     */
    suspend fun downloadModelSync(modelInfo: ModelInfo): ModelDownloadResult

    /**
     * Delete a downloaded model.
     */
    suspend fun deleteModel(modelName: String): Boolean

    /**
     * Get total size of all downloaded models.
     */
    suspend fun getTotalModelSize(): Long
}

/**
 * Default model configurations.
 */
object WhisperModels {
    // Microsoft Olive-generated Whisper model - single file, simpler architecture
    // https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/whisper/local/android
    // Using direct raw.githubusercontent.com URL to avoid redirect issues
    private const val OLIVE_URL = "https://raw.githubusercontent.com/microsoft/onnxruntime-inference-examples/main/mobile/examples/whisper/local/android/app/src/main/res/raw/whisper_cpu_int8_model.onnx"

    val WHISPER_TINY_EN = ModelInfo(
        name = "whisper-olive-tiny-en",
        version = "2.0",
        sizeBytes = 75_000_000L,  // ~71MB single file
        downloadUrl = OLIVE_URL,
        checksum = null,
        files = listOf(
            ModelFileInfo(
                filename = "model.onnx",
                downloadUrl = OLIVE_URL,
                sizeBytes = 75_000_000L
            )
        )
    )

    // To run a larger checkpoint, host your own Olive export of whisper-base.en that keeps the
    // same all-in-one graph I/O contract the Android engine binds to (inputs audio_pcm/
    // min_length/max_length/num_beams/num_return_sequences/length_penalty/repetition_penalty)
    // and point a new ModelInfo at it. WHISPER_TINY_EN is the default because its model file is
    // public and needs no hosting.
}

/**
 * NVIDIA models run through sherpa-onnx (k2-fsa, Apache-2.0).
 *
 * These are 600M-parameter FastConformer models, ~15x larger than Whisper tiny.en, so
 * the download is correspondingly bigger. sherpa-onnx ships its own ONNX Runtime, does
 * its own feature extraction and transducer decode, and runs the same model on both
 * Android and iOS, so [MelSpectrogramProcessor] and [WhisperTokenizer] are not used on
 * these paths.
 *
 * The host app supplies the sherpa runtime (see `NativeAsrProvider`); the library only
 * carries the model definitions and routes to the registered provider.
 */
object SherpaModels {
    // sherpa-onnx exports of NVIDIA Parakeet-TDT-0.6b-v3, int8 quantized.
    // Distributed by k2-fsa as a single tar.bz2 on the asr-models release; the host app
    // stages encoder/decoder/joiner + tokens.txt into the model dir.
    // https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2
    private const val PARAKEET_BASE =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

    val PARAKEET_TDT_0_6B_V3 = ModelInfo(
        name = "parakeet-tdt-0.6b-v3-int8",
        version = "1.0",
        sizeBytes = 642_000_000L,  // encoder 622M + decoder 12M + joiner 6.1M + tokens
        downloadUrl = "$PARAKEET_BASE.tar.bz2",
        checksum = null,
        files = listOf(
            ModelFileInfo("encoder.int8.onnx", "$PARAKEET_BASE.tar.bz2", 622_000_000L),
            ModelFileInfo("decoder.int8.onnx", "$PARAKEET_BASE.tar.bz2", 12_000_000L),
            ModelFileInfo("joiner.int8.onnx", "$PARAKEET_BASE.tar.bz2", 6_100_000L),
            ModelFileInfo("tokens.txt", "$PARAKEET_BASE.tar.bz2", 92_000L),
        ),
        backend = AsrBackend.SHERPA_TRANSDUCER,
    )

    // NVIDIA Nemotron-Speech-Streaming-En-0.6b. The on-device artifact path is still
    // being settled (the Microsoft ONNX port is wrapped in Foundry-Local's pipeline and
    // has no mobile bindings yet); populated once a sherpa-loadable export exists.
    // Placeholder kept so the backend type and selection plumbing are testable today.
    val NEMOTRON_STREAMING_EN_0_6B = ModelInfo(
        name = "nemotron-speech-streaming-en-0.6b",
        version = "0.0",
        sizeBytes = 700_000_000L,
        downloadUrl = "",
        checksum = null,
        files = emptyList(),
        backend = AsrBackend.SHERPA_STREAMING,
    )
}

/**
 * Every model the library knows how to describe, keyed by [ModelInfo.name].
 *
 * Used to resolve a [TranscriptionConfig.modelName] to its [ModelInfo] (and thus its
 * [AsrBackend]) so a platform [TranscriptionEngine] can dispatch to the right backend.
 */
object Models {
    val ALL: List<ModelInfo> = listOf(
        WhisperModels.WHISPER_TINY_EN,
        SherpaModels.PARAKEET_TDT_0_6B_V3,
        SherpaModels.NEMOTRON_STREAMING_EN_0_6B,
    )

    /** Look up a model by name, or null if unknown. */
    fun byName(name: String): ModelInfo? = ALL.firstOrNull { it.name == name }

    /**
     * The backend a model name maps to. Unknown names fall back to [AsrBackend.WHISPER_OLIVE],
     * preserving the pre-existing behaviour where any configured name ran the Whisper path.
     */
    fun backendFor(name: String): AsrBackend =
        byName(name)?.backend ?: AsrBackend.WHISPER_OLIVE
}
