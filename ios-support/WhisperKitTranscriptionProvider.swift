import Foundation
import Earshot
import WhisperKit

/// Reference Swift glue: a WhisperKit-backed implementation of Earshot's
/// `NativeTranscriptionProvider` seam.
///
/// The shared Kotlin `TranscriptionEngine` (iOS actual) delegates every call to the
/// instance registered in `NativeTranscriptionProviderHolder.shared.implementation`.
/// This class is that instance: it owns a lazily-loaded `WhisperKit` pipeline and
/// drives it from the audio file path the shared `AudioExtractor` produces.
///
/// Register it once at app launch:
///
///     NativeTranscriptionProviderHolder.shared.implementation =
///         WhisperKitTranscriptionProvider()
///
/// Model handling: WhisperKit downloads + caches its CoreML model internally on
/// first load, so the shared `ModelDownloader` / `WhisperTokenizer` iOS actuals
/// stay no-ops. `ensureModelDownloaded()` here maps onto loading that pipeline.
///
/// Concurrency: bookkeeping (the cached pipeline + in-flight load task) is
/// `@MainActor`-isolated so there is no manual locking in async contexts; the
/// heavy WhisperKit work runs inside an awaited `Task`. Bridged Kotlin `suspend`
/// methods that return `Boolean` surface in Swift as `KotlinBoolean`.
@MainActor
final class WhisperKitTranscriptionProvider: NSObject, NativeTranscriptionProvider {

    /// Loaded WhisperKit pipeline. `nil` until a model has loaded.
    private var pipe: WhisperKit?
    /// In-flight load, so concurrent callers await the same work.
    private var loadTask: Task<WhisperKit, Error>?
    /// Thread-safe readiness hint, mirrored from `pipe` so the synchronous,
    /// any-thread `isReady()` bridge never has to touch main-actor state.
    private let readyFlag = ReadyFlag()

    /// Optional WhisperKit model name (e.g. "tiny.en"). `nil` lets WhisperKit pick its
    /// device-appropriate default. The benchmark pins this so it matches the Android model.
    private let modelName: String?

    init(modelName: String? = nil) {
        self.modelName = modelName
        super.init()
    }

    // MARK: NativeTranscriptionProvider

    /// True once the WhisperKit pipeline has loaded a model. Safe from any thread;
    /// callers (the shared engine) use this only as a hint.
    nonisolated func isReady() -> Bool { readyFlag.value }

    /// Load (download + init) the WhisperKit pipeline if needed.
    /// Returns true once a model is loaded and ready.
    func ensureModelDownloaded() async throws -> KotlinBoolean {
        _ = try await loadedPipe()
        return KotlinBoolean(bool: true)
    }

    /// Transcribe the 16kHz mono WAV at `audioPath`, returning the recognized text.
    /// Throws on failure; the shared engine maps the error into a `TranscriptionEngineResult.Error`.
    func transcribe(audioPath: String) async throws -> String {
        let pipe = try await loadedPipe()
        // WhisperKit 0.9.x returns an array of TranscriptionResult; join their text.
        let results = try await pipe.transcribe(audioPath: audioPath)
        let text = results.map { $0.text }.joined(separator: " ")
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Pipeline loading

    private func loadedPipe() async throws -> WhisperKit {
        if let pipe { return pipe }
        if let loadTask { return try await loadTask.value }
        let pinned = modelName
        let task = Task { try await WhisperKit(model: pinned) }
        loadTask = task
        do {
            let created = try await task.value
            pipe = created
            loadTask = nil
            readyFlag.value = true
            return created
        } catch {
            loadTask = nil
            throw error
        }
    }
}

/// Tiny thread-safe boolean used so `isReady()` can be read from any thread
/// without crossing into the provider's main-actor state.
private final class ReadyFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}
