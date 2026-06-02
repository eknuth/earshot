import Foundation
import Speech
#if canImport(UIKit)
import UIKit
#endif

/// Benchmark for Apple's built-in on-device recognizer (`SFSpeechRecognizer`), so the WhisperKit
/// numbers have something to stand against on the same device and the same clips.
///
/// It is scored on accuracy (word error rate) only. Apple's recognition runs in a system daemon,
/// out of this process, so the in-process memory probe and timings used for the other runtimes
/// are not comparable here. The raw run is flagged `accuracyOnly` and the visual omits it from the
/// speed and memory charts.
///
/// Triggered by launching the app with `--earshot-bench-apple`. Requires the speech-recognition
/// permission (NSSpeechRecognitionUsageDescription); the first launch prompts for it.
@MainActor
enum AppleSpeechBench {

    static func run() async {
        do {
            try await execute()
            print("EARSHOT_BENCH_DONE")
        } catch {
            print("EARSHOT_BENCH_ERROR: \(error)")
        }
        exit(0)
    }

    private static func execute() async throws {
        guard let manifestURL = Bundle.main.url(forResource: "manifest", withExtension: "json", subdirectory: "fixtures"),
              let manifestData = try? Data(contentsOf: manifestURL),
              let manifest = try JSONSerialization.jsonObject(with: manifestData) as? [String: Any],
              let clips = manifest["clips"] as? [[String: Any]] else {
            throw err("fixtures/manifest.json not found in bundle")
        }

        let status: SFSpeechRecognizerAuthorizationStatus = await withCheckedContinuation { cont in
            SFSpeechRecognizer.requestAuthorization { cont.resume(returning: $0) }
        }
        guard status == .authorized else {
            throw err("speech recognition not authorized (status \(status.rawValue))")
        }
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: "en-US")) else {
            throw err("no recognizer for en-US")
        }
        guard recognizer.supportsOnDeviceRecognition else {
            throw err("on-device recognition unavailable for en-US on this device")
        }

        var results: [[String: Any]] = []
        for clip in clips {
            guard let id = clip["id"] as? String,
                  let wavURL = Bundle.main.url(forResource: id, withExtension: "wav", subdirectory: "fixtures/audio") else {
                continue
            }
            let start = Date()
            let hypothesis = (try? await transcribe(recognizer: recognizer, url: wavURL)) ?? ""
            let processingMs = Int(Date().timeIntervalSince(start) * 1000)
            results.append(["id": id, "hypothesis": hypothesis, "processingMs": processingMs])
        }

        #if targetEnvironment(simulator)
        let provenance = "simulator"
        #else
        let provenance = "real-device"
        #endif
        var device = "iOS device"
        #if canImport(UIKit)
        device = UIDevice.current.name
        let osVersion = "iOS \(UIDevice.current.systemVersion)"
        #else
        let osVersion = "iOS"
        #endif

        let raw: [String: Any] = [
            "runtime": "Apple Speech (on-device)",
            "platform": "iOS",
            "model": "SFSpeechRecognizer en-US",
            "device": device,
            "provenance": provenance,
            "osVersion": osVersion,
            "loadMs": 0,
            "peakMemoryBytes": 0,
            "accuracyOnly": true,
            "clips": results,
        ]
        let out = try JSONSerialization.data(withJSONObject: raw, options: [.prettyPrinted, .sortedKeys])
        let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("raw-apple.json")
        try out.write(to: dest)
        print("EARSHOT_BENCH_OUTPUT: \(dest.path)")
    }

    /// Transcribe a single file on-device, returning the best transcription.
    private static func transcribe(recognizer: SFSpeechRecognizer, url: URL) async throws -> String {
        let request = SFSpeechURLRecognitionRequest(url: url)
        request.requiresOnDeviceRecognition = true
        request.shouldReportPartialResults = false
        request.taskHint = .dictation
        return try await withCheckedThrowingContinuation { cont in
            var resumed = false
            recognizer.recognitionTask(with: request) { result, error in
                if let error = error {
                    if !resumed { resumed = true; cont.resume(throwing: error) }
                    return
                }
                guard let result = result, result.isFinal else { return }
                if !resumed { resumed = true; cont.resume(returning: result.bestTranscription.formattedString) }
            }
        }
    }

    private static func err(_ message: String) -> NSError {
        NSError(domain: "AppleSpeechBench", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
}
