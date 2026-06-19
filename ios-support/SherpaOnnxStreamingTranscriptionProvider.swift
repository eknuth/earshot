import Foundation
import AVFoundation
import Earshot

/// sherpa-onnx-backed implementation of Earshot's `NativeTranscriptionProvider`, for the
/// NVIDIA streaming models (Nemotron-Speech-Streaming). The streaming counterpart to
/// `SherpaOnnxTranscriptionProvider`: Nemotron is a cache-aware streaming FastConformer +
/// RNNT, so it runs through the online `SherpaOnnxRecognizer` rather than the offline one.
///
/// The benchmark harness is clip-at-a-time, so this feeds the whole WAV into one online
/// stream, signals `inputFinished`, drains the decode loop, and returns the final text.
/// The model is still streaming under the hood (cache-aware chunks); we just present a
/// full utterance so the WER is comparable to the offline runs.
///
/// Register it instead of `WhisperKitTranscriptionProvider` to run Nemotron:
///
///     NativeTranscriptionProviderHolder.shared.implementation =
///         SherpaOnnxStreamingTranscriptionProvider(modelDir: nemotronModelDir)
///
/// Integration (Xcode, not buildable from the KMP gradle build) mirrors
/// `SherpaOnnxTranscriptionProvider`: the same sherpa-onnx xcframeworks, bridging header,
/// and `SherpaOnnx.swift` helper (which already vends the online config functions and the
/// `SherpaOnnxRecognizer` online class). Bundle (or stage) the model dir with
/// encoder.int8.onnx / decoder.int8.onnx / joiner.int8.onnx / tokens.txt as `modelDir`.
@MainActor
final class SherpaOnnxStreamingTranscriptionProvider: NSObject, NativeTranscriptionProvider {

    private let modelDir: String
    private let numThreads: Int
    private var recognizer: SherpaOnnxRecognizer?
    private let readyFlag = StreamingReadyFlag()

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
        // Fresh stream per clip so state from the previous utterance does not leak.
        recognizer.reset()
        recognizer.acceptWaveform(samples: samples, sampleRate: 16_000)
        // Flush: no more audio is coming, so sherpa can pad/finish the last chunk.
        recognizer.inputFinished()
        // Drain the cache-aware decode loop until all buffered frames are consumed.
        while recognizer.isReady() {
            recognizer.decode()
        }
        let text = recognizer.getResult().text
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Recognizer

    private func loadedRecognizer() throws -> SherpaOnnxRecognizer {
        if let recognizer { return recognizer }

        let transducer = sherpaOnnxOnlineTransducerModelConfig(
            encoder: "\(modelDir)/encoder.int8.onnx",
            decoder: "\(modelDir)/decoder.int8.onnx",
            joiner: "\(modelDir)/joiner.int8.onnx"
        )
        // No modelType override: let sherpa auto-detect the NeMo streaming transducer path
        // from the encoder metadata, the same reason the offline Parakeet provider leaves it
        // unset.
        let modelConfig = sherpaOnnxOnlineModelConfig(
            tokens: "\(modelDir)/tokens.txt",
            transducer: transducer,
            numThreads: numThreads,
            provider: "cpu"
        )
        let featConfig = sherpaOnnxFeatureConfig(sampleRate: 16_000, featureDim: 80)
        // Endpointing off: the harness feeds a single bounded clip and reads one final
        // result, so utterance segmentation would only fragment short clips.
        var config = sherpaOnnxOnlineRecognizerConfig(
            featConfig: featConfig,
            modelConfig: modelConfig,
            enableEndpoint: false,
            decodingMethod: "greedy_search"
        )
        let created = SherpaOnnxRecognizer(config: &config)
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
            throw NSError(domain: "SherpaOnnxStreaming", code: -1,
                          userInfo: [NSLocalizedDescriptionKey: "could not allocate audio buffer"])
        }
        try file.read(into: buffer)
        guard let channelData = buffer.floatChannelData else {
            throw NSError(domain: "SherpaOnnxStreaming", code: -2,
                          userInfo: [NSLocalizedDescriptionKey: "no float channel data"])
        }
        return Array(UnsafeBufferPointer(start: channelData[0], count: Int(buffer.frameLength)))
    }
}

/// Thread-safe boolean so `isReady()` can be read off the main actor.
private final class StreamingReadyFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var _value = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _value }
        set { lock.lock(); _value = newValue; lock.unlock() }
    }
}
