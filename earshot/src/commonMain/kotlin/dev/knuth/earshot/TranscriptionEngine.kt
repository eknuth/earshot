package dev.knuth.earshot

/**
 * Result of a transcription operation.
 */
sealed class TranscriptionEngineResult {
    data class Success(
        val text: String,
        val language: String?,
        val confidence: Float?,
        val processingTimeMs: Long
    ) : TranscriptionEngineResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : TranscriptionEngineResult()
}

/**
 * Model initialization status.
 */
sealed class ModelStatus {
    data object NotDownloaded : ModelStatus()
    data class Downloading(val progress: Float) : ModelStatus()
    data object Ready : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

/**
 * Configuration for the transcription engine.
 */
data class TranscriptionConfig(
    val modelName: String = "whisper-olive-tiny-en",
    val maxAudioLengthSeconds: Int = 60,
    val language: String? = null,  // null = auto-detect
    val useGpu: Boolean = false
)

/**
 * Platform-specific on-device transcription engine (Whisper).
 *
 * Android runs an ONNX export of Whisper via ONNX Runtime; iOS runs Whisper via
 * WhisperKit (CoreML). Both run fully on-device with no external services.
 */
expect class TranscriptionEngine {
    /**
     * Initialize the transcription engine.
     * This loads the model into memory. May take several seconds.
     *
     * @return true if initialization successful, false otherwise
     */
    suspend fun initialize(config: TranscriptionConfig = TranscriptionConfig()): Boolean

    /**
     * Transcribe audio from a WAV file.
     *
     * @param audioPath Path to the 16kHz mono WAV file
     * @return TranscriptionEngineResult with transcribed text or error
     */
    suspend fun transcribe(audioPath: String): TranscriptionEngineResult

    /**
     * Check if the engine is initialized and ready to transcribe.
     */
    fun isReady(): Boolean

    /**
     * Get current model status.
     */
    fun getModelStatus(): ModelStatus

    /**
     * Download the model if not present.
     * Progress updates can be observed via getModelStatus().
     *
     * @return true if model is available after this call
     */
    suspend fun ensureModelDownloaded(): Boolean

    /**
     * Release resources. Call when done with transcription.
     */
    fun release()

    /**
     * Check if the transcription engine is available on this platform.
     */
    fun isAvailable(): Boolean
}
