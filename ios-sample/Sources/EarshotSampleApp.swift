import SwiftUI
import UniformTypeIdentifiers
import Earshot

/// Smallest honest demo of the Earshot SDK on iOS: pick an audio or video file,
/// transcribe it entirely on-device through WhisperKit, show the text and timing.
@main
struct EarshotSampleApp: App {
    init() {
        // Benchmark mode: transcribe the bundled fixtures, write raw-ios.json, exit. Used by the
        // on-device benchmark harness (--earshot-bench), not part of the normal demo.
        if CommandLine.arguments.contains("--earshot-bench") {
            Task { await BenchRunner.run() }
            return
        }
        // Benchmark mode for Apple's built-in on-device recognizer, for an accuracy comparison
        // against WhisperKit on the same device and clips.
        if CommandLine.arguments.contains("--earshot-bench-apple") {
            Task { await AppleSpeechBench.run() }
            return
        }
        // Benchmark mode for NVIDIA Parakeet-TDT via sherpa-onnx, the same model that runs on
        // Android, for a cross-runtime comparison against Whisper on the same device and clips.
        if CommandLine.arguments.contains("--earshot-bench-parakeet") {
            Task { await ParakeetBenchRunner.run() }
            return
        }
        // Benchmark mode for NVIDIA Nemotron-Speech-Streaming via sherpa-onnx (the streaming
        // recognizer), the same model that runs on Android, for a cross-runtime comparison.
        if CommandLine.arguments.contains("--earshot-bench-nemotron") {
            Task { await NemotronBenchRunner.run() }
            return
        }
        // Register the Swift WhisperKit backend with the shared seam, once, at launch.
        NativeTranscriptionProviderHolder.shared.implementation = WhisperKitTranscriptionProvider()
    }

    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

struct ContentView: View {
    @State private var status = "Pick an audio or video file to transcribe on-device."
    @State private var transcript = ""
    @State private var busy = false
    @State private var picking = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Earshot").font(.largeTitle.bold())
                Text("On-device speech to text. Whisper via WhisperKit / CoreML. Nothing leaves the phone.")
                    .font(.subheadline).foregroundStyle(.secondary)

                Button(busy ? "Working..." : "Pick audio / video") { picking = true }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)

                Button("Transcribe bundled sample") {
                    if let url = Bundle.main.url(forResource: "sample", withExtension: "wav") {
                        transcribe(url)
                    } else {
                        status = "Bundled sample.wav not found in app bundle"
                    }
                }
                .buttonStyle(.bordered)
                .disabled(busy)

                if busy { ProgressView().frame(maxWidth: .infinity) }

                Text(status).font(.footnote).foregroundStyle(.secondary)

                if !transcript.isEmpty {
                    Divider()
                    Text(transcript).font(.body)
                }
            }
            .padding(24)
        }
        .fileImporter(isPresented: $picking,
                      allowedContentTypes: [.audio, .movie, .mpeg4Movie, .wav],
                      allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first { transcribe(url) }
            case .failure(let error):
                status = "Pick failed: \(error.localizedDescription)"
            }
        }
    }

    private func transcribe(_ url: URL) {
        busy = true
        transcript = ""
        Task {
            defer { busy = false }
            do {
                // Copy the security-scoped pick into a path Earshot can read.
                let needsStop = url.startAccessingSecurityScopedResource()
                defer { if needsStop { url.stopAccessingSecurityScopedResource() } }
                let local = FileManager.default.temporaryDirectory
                    .appendingPathComponent("input_" + url.lastPathComponent)
                try? FileManager.default.removeItem(at: local)
                try FileManager.default.copyItem(at: url, to: local)

                let transcriber = OnDeviceTranscriber(engine: TranscriptionEngine(),
                                                      audioExtractor: AudioExtractor())
                status = "Loading model (first run downloads it)..."
                _ = try await transcriber.prepare()

                status = "Transcribing on-device..."
                let scratch = FileManager.default.temporaryDirectory
                    .appendingPathComponent("scratch.wav").path
                let result = try await transcriber.transcribeMedia(mediaPath: local.path,
                                                                   scratchWavPath: scratch)
                transcriber.release()

                if let ok = result as? TranscriptionEngineResult.Success {
                    transcript = ok.text
                    status = "Done on-device in \(ok.processingTimeMs) ms"
                } else if let err = result as? TranscriptionEngineResult.Error {
                    status = "Error: \(err.message)"
                }
            } catch {
                status = "Error: \(error.localizedDescription)"
            }
        }
    }
}
