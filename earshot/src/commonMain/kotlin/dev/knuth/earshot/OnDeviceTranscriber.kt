package dev.knuth.earshot

/**
 * One small API over the cross-platform transcription seam.
 *
 * [OnDeviceTranscriber] wires the platform [AudioExtractor] and [TranscriptionEngine]
 * together so a caller can hand it a media file and get text back, on-device. Both
 * dependencies are injected because each platform builds them differently:
 *
 *  - Android: construct [TranscriptionEngine], point it at a downloaded model with
 *    `setModelPath(...)` (see [ModelDownloader] and the sample app), then pass it here.
 *  - iOS: register a Swift WhisperKit provider on
 *    [NativeTranscriptionProviderHolder] at launch, then pass a fresh
 *    [TranscriptionEngine] here.
 *
 * Nothing in this class touches the network or leaves the device. The only network
 * call in the whole pipeline is the one-time model download, owned by the platform
 * (the Android [ModelDownloader] or WhisperKit's own model fetch on iOS).
 */
class OnDeviceTranscriber(
    private val engine: TranscriptionEngine,
    private val audioExtractor: AudioExtractor,
) {
    /**
     * Load the model into memory so transcription can start. Call once before the
     * first transcribe. Returns false if the model is not present or fails to load.
     */
    suspend fun prepare(config: TranscriptionConfig = TranscriptionConfig()): Boolean =
        engine.initialize(config)

    /** True once a model is loaded and ready to transcribe. */
    fun isReady(): Boolean = engine.isReady()

    /** Current model status (not downloaded, downloading, ready, or error). */
    fun modelStatus(): ModelStatus = engine.getModelStatus()

    /**
     * Transcribe a 16kHz mono WAV file.
     *
     * @param wavPath path to a 16kHz mono PCM WAV file
     */
    suspend fun transcribeAudio(wavPath: String): TranscriptionEngineResult =
        engine.transcribe(wavPath)

    /**
     * Extract audio from a video (or any AV file) on-device, then transcribe it.
     *
     * @param mediaPath path to the input video / audio file
     * @param scratchWavPath path where the extracted 16kHz mono WAV is written
     */
    suspend fun transcribeMedia(
        mediaPath: String,
        scratchWavPath: String,
    ): TranscriptionEngineResult =
        when (val extracted = audioExtractor.extract(mediaPath, scratchWavPath)) {
            is AudioExtractionResult.Success -> engine.transcribe(extracted.audioPath)
            is AudioExtractionResult.Error ->
                TranscriptionEngineResult.Error(extracted.message, extracted.cause)
        }

    /** Release the model and any native resources. */
    fun release() = engine.release()
}
