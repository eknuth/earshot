package dev.eknuth.earshot

/**
 * Result of an audio extraction operation.
 */
sealed class AudioExtractionResult {
    data class Success(
        val audioPath: String,
        val sampleRate: Int,
        val channels: Int,
        val durationMs: Long,
        val extractionTimeMs: Long
    ) : AudioExtractionResult()

    data class Error(
        val message: String,
        val cause: Throwable? = null
    ) : AudioExtractionResult()
}

/**
 * Audio format specifications for transcription engines.
 */
object AudioFormat {
    const val SAMPLE_RATE_16KHZ = 16000
    const val CHANNELS_MONO = 1
    const val BITS_PER_SAMPLE = 16
}

/**
 * Platform-specific audio extractor.
 * Extracts audio from video files and converts to WAV format suitable for transcription.
 */
expect class AudioExtractor {
    /**
     * Extract audio from a video file.
     *
     * @param videoPath Path to the input video file
     * @param outputPath Path for the output WAV file
     * @param targetSampleRate Target sample rate (default 16kHz for Whisper)
     * @param targetChannels Target number of channels (default mono)
     * @param maxDurationSeconds Maximum duration to extract (null for full audio)
     * @return AudioExtractionResult indicating success or failure
     */
    suspend fun extract(
        videoPath: String,
        outputPath: String,
        targetSampleRate: Int = AudioFormat.SAMPLE_RATE_16KHZ,
        targetChannels: Int = AudioFormat.CHANNELS_MONO,
        maxDurationSeconds: Int? = null
    ): AudioExtractionResult

    /**
     * Check if the extractor is available on this platform.
     */
    fun isAvailable(): Boolean
}
