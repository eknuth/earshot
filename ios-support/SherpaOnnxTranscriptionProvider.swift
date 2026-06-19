import Foundation
import AVFoundation
import Earshot

/// sherpa-onnx-backed implementation of Earshot's `NativeTranscriptionProvider`, for the
/// NVIDIA transducer models (Parakeet-TDT). The iOS counterpart to the Android
/// `SherpaOnnxProvider`.
///
/// The iOS `TranscriptionEngine` (Kotlin actual) delegates every call to whichever
/// provider is registered in `NativeTranscriptionProviderHolder`. Register this instead
/// of `WhisperKitTranscriptionProvider` to run Parakeet:
///
///     NativeTranscriptionProviderHolder.shared.implementation =
///         SherpaOnnxTranscriptionProvider(modelDir: parakeetModelDir)
///
/// Integration (Xcode, not buildable from the KMP gradle build):
///   1. Add the sherpa-onnx Swift package: github.com/k2-fsa/sherpa-onnx (1.10.0+),
///      which vends the C API + xcframework.
///   2. Add the `SherpaOnnx.swift` helper + bridging header from sherpa-onnx's
///      swift-api-examples to the app target (it is example glue, not shipped in the
///      package). This file calls that helper's `sherpaOnnxOffline*` functions and
///      `SherpaOnnxOfflineRecognizer`.
///   3. Bundle (or stage) the model dir with encoder.int8.onnx / decoder.int8.onnx /
///      joiner.int8.onnx / tokens.txt and pass its path as `modelDir`.
@MainActor
final class SherpaOnnxTranscriptionProvider: NSObject, NativeTranscriptionProvider {

    private let modelDir: String
    private let numThreads: Int
    private var recognizer: SherpaOnnxOfflineRecognizer?
    private let readyFlag = ReadyFlag()

    init(modelDir: String, numThreads: Int = 4) {
        self.modelDir = modelDir
        self.numThreads = numThreads
        super.init()
    }

    // MARK: NativeTranscriptionProvider

    nonisolated func isReady() -> Bool { readyFlag.value }

    func ensureModelDownloaded() async throws -> KotlinBoolean {
        _ = try loadedRecognizer()
        return KotlinBoolean(bool: true)
    }

    func transcribe(audioPath: String) async throws -> String {
        let recognizer = try loadedRecognizer()
        let samples = try readWav16kMono(path: audioPath)
        let result = recognizer.decode(samples: samples, sampleRate: 16_000)
        return result.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Recognizer

    private func loadedRecognizer() throws -> SherpaOnnxOfflineRecognizer {
        if let recognizer { return recognizer }

        let transducer = sherpaOnnxOfflineTransducerModelConfig(
            encoder: "\(modelDir)/encoder.int8.onnx",
            decoder: "\(modelDir)/decoder.int8.onnx",
            joiner: "\(modelDir)/joiner.int8.onnx"
        )
        // No modelType override: let sherpa auto-detect from the encoder metadata. Parakeet-TDT
        // advertises model_type=EncDecRNNTBPEModel and stores vocab_size in the encoder, so the
        // NeMo-aware transducer path is required. Forcing "transducer" routes to the generic
        // decoder that looks for vocab_size in the decoder (which this model does not carry).
        let modelConfig = sherpaOnnxOfflineModelConfig(
            tokens: "\(modelDir)/tokens.txt",
            transducer: transducer,
            numThreads: numThreads,
            provider: "cpu"
        )
        let featConfig = sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 80)
        var config = sherpaOnnxOfflineRecognizerConfig(
            featConfig: featConfig,
            modelConfig: modelConfig
        )
        let created = SherpaOnnxOfflineRecognizer(config: &config)
        recognizer = created
        readyFlag.value = true
        return created
    }

    /// Read a 16kHz mono WAV into normalized Float samples. The shared `AudioExtractor`
    /// already produces 16kHz mono, so this is a straight read.
    private func readWav16kMono(path: String) throws -> [Float] {
        let url = URL(fileURLWithPath: path)
        let file = try AVAudioFile(forReading: url)
        let format = file.processingFormat
        let frameCount = AVAudioFrameCount(file.length)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frameCount) else {
            throw NSError(domain: "SherpaOnnx", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "could not allocate audio buffer"])
        }
        try file.read(into: buffer)
        guard let channelData = buffer.floatChannelData else {
            throw NSError(domain: "SherpaOnnx", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "no float channel data"])
        }
        return Array(UnsafeBufferPointer(start: channelData[0], count: Int(buffer.frameLength)))
    }
}

/// Thread-safe boolean so `isReady()` can be read off the main actor.
private final class ReadyFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}
