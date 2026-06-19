import Foundation
import Earshot
#if canImport(UIKit)
import UIKit
#endif

/// On-device benchmark for NVIDIA Nemotron-Speech-Streaming-En-0.6b via sherpa-onnx. Mirror
/// of `ParakeetBenchRunner` so the :benchmark scorer judges both identically; only the
/// model, provider (streaming `SherpaOnnxRecognizer`) and output file differ. Writes
/// raw-nemotron-ios.json.
///
/// Triggered by launching with `--earshot-bench-nemotron`; see `EarshotSampleApp`.
///
/// The ~632MB model is not bundled in git. Add the extracted
/// sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25 directory
/// (encoder.int8.onnx / decoder.int8.onnx / joiner.int8.onnx / tokens.txt) to the app
/// target as a folder reference named `nemotron`, and add the sherpa-onnx SPM package +
/// helper (see `SherpaOnnxStreamingTranscriptionProvider`).
@MainActor
enum NemotronBenchRunner {

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
            throw NSError(domain: "bench", code: 1, userInfo: [NSLocalizedDescriptionKey: "fixtures/manifest.json not found in bundle"])
        }

        guard let modelDir = Bundle.main.url(forResource: "nemotron", withExtension: nil)?.path else {
            throw NSError(domain: "bench", code: 2, userInfo: [NSLocalizedDescriptionKey: "nemotron model dir not in bundle (see NemotronBenchRunner doc)"])
        }

        // Timed cold load.
        let provider = SherpaOnnxStreamingTranscriptionProvider(modelDir: modelDir)
        let loadStart = Date()
        _ = try await provider.ensureModelDownloaded()
        let loadMs = Int(Date().timeIntervalSince(loadStart) * 1000)

        var peakMemory: UInt64 = currentMemoryBytes()
        var results: [[String: Any]] = []
        for clip in clips {
            guard let id = clip["id"] as? String,
                  let wavURL = Bundle.main.url(forResource: id, withExtension: "wav", subdirectory: "fixtures/audio") else {
                continue
            }
            let start = Date()
            let hypothesis = (try? await provider.transcribe(audioPath: wavURL.path)) ?? ""
            let processingMs = Int(Date().timeIntervalSince(start) * 1000)
            results.append(["id": id, "hypothesis": hypothesis, "processingMs": processingMs])
            peakMemory = max(peakMemory, currentMemoryBytes())
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
            "runtime": "sherpa-onnx",
            "platform": "iOS",
            "model": "nemotron-speech-streaming-en-0.6b (int8)",
            "device": device,
            "provenance": provenance,
            "osVersion": osVersion,
            "loadMs": loadMs,
            "peakMemoryBytes": Int(peakMemory),
            "clips": results,
        ]
        let out = try JSONSerialization.data(withJSONObject: raw, options: [.prettyPrinted, .sortedKeys])
        let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("raw-nemotron-ios.json")
        try out.write(to: dest)
        print("EARSHOT_BENCH_OUTPUT: \(dest.path)")
    }

    private static func currentMemoryBytes() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size) / 4
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? info.phys_footprint : 0
    }
}
