package dev.knuth.earshot

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
    val files: List<ModelFileInfo> = emptyList()  // Additional files
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
