package dev.eknuth.earshot

import dev.eknuth.earshot.currentTimeMillis

/**
 * Provider seam for native (Swift) transcription backends on iOS.
 *
 * The shared module has no way to call WhisperKit / CoreML directly, so the iOS
 * app injects a Swift implementation of this interface at launch. A Kotlin
 * singleton ([NativeTranscriptionProviderHolder]) holds a nullable implementation
 * that the app sets once during composition.
 *
 * Kotlin/Native singletons holding Swift-implemented interfaces work fine; the
 * Swift side conforms to the generated Obj-C protocol and implements the
 * `suspend` methods via completion handlers.
 *
 * The actual [TranscriptionEngine] below delegates to the registered provider
 * and falls back to "not available" results when none is set (e.g. unit tests,
 * or before the app finishes wiring).
 */
interface NativeTranscriptionProvider {
    /**
     * Transcribe the audio file at [audioPath] (a 16kHz mono WAV produced by the
     * shared [AudioExtractor]). Returns the recognized transcript text.
     *
     * Implementations should throw on failure; the engine maps a thrown error
     * into [TranscriptionEngineResult.Error].
     */
    suspend fun transcribe(audioPath: String): String

    /** True once the underlying model is loaded and ready to transcribe. */
    fun isReady(): Boolean

    /**
     * Ensure the ASR model is downloaded / loaded. Returns true if the model is
     * available after this call. WhisperKit fetches + caches the CoreML model
     * internally, so this typically wraps its model-load step.
     */
    suspend fun ensureModelDownloaded(): Boolean
}

/**
 * Settable singleton holder for the [NativeTranscriptionProvider].
 *
 * The iOS app sets [implementation] exactly once during composition
 * (see `WhisperKitTranscriptionProvider` + `AppContainer`). Kept as a top-level
 * `object` so both the app (writer) and [TranscriptionEngine] (reader) share it.
 */
object NativeTranscriptionProviderHolder {
    var implementation: NativeTranscriptionProvider? = null
}

/**
 * iOS implementation of [TranscriptionEngine].
 *
 * This is a thin delegating seam: it forwards every call to the registered
 * [NativeTranscriptionProvider] (a Swift WhisperKit-backed engine) and returns
 * the existing "not available" results when no provider has been registered.
 *
 * Note: [WhisperTokenizer] and [ModelDownloader] iOS actuals remain dead stubs
 * on purpose. WhisperKit performs tokenization and model fetch/caching itself,
 * so the shared module never drives those steps on iOS.
 */
actual class TranscriptionEngine {

    private val provider: NativeTranscriptionProvider?
        get() = NativeTranscriptionProviderHolder.implementation

    actual suspend fun initialize(config: TranscriptionConfig): Boolean {
        // WhisperKit loads its model lazily; "initialize" maps to ensuring the
        // model is present. No provider -> not initialized.
        val p = provider ?: return false
        return try {
            p.ensureModelDownloaded()
        } catch (e: Throwable) {
            false
        }
    }

    actual suspend fun transcribe(audioPath: String): TranscriptionEngineResult {
        val p = provider
            ?: return TranscriptionEngineResult.Error(
                "iOS transcription provider not registered"
            )
        val start = currentTimeMillis()
        return try {
            val text = p.transcribe(audioPath)
            TranscriptionEngineResult.Success(
                text = text,
                language = null,
                confidence = null,
                processingTimeMs = currentTimeMillis() - start
            )
        } catch (e: Throwable) {
            TranscriptionEngineResult.Error(
                message = "iOS transcription failed: ${e.message}",
                cause = e
            )
        }
    }

    actual fun isReady(): Boolean = provider?.isReady() ?: false

    actual fun getModelStatus(): ModelStatus {
        val p = provider ?: return ModelStatus.Error("iOS transcription provider not registered")
        return if (p.isReady()) ModelStatus.Ready else ModelStatus.NotDownloaded
    }

    actual suspend fun ensureModelDownloaded(): Boolean {
        val p = provider ?: return false
        return try {
            p.ensureModelDownloaded()
        } catch (e: Throwable) {
            false
        }
    }

    actual fun release() {
        // Nothing to release on the Kotlin side; the Swift provider owns the
        // WhisperKit instance and its lifecycle.
    }

    actual fun isAvailable(): Boolean = provider != null
}
