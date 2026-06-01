import Foundation
import Earshot
#if canImport(UIKit)
import UIKit
#endif

/// On-device benchmark for the iOS (WhisperKit / CoreML) path. Mirror of the Android
/// instrumented runner: it transcribes the bundled fixture clips, captures raw hypotheses plus
/// load time, per-clip processing time and peak memory, and writes raw-ios.json. The :benchmark
/// scorer computes word error rate offline so both runtimes are judged by identical math.
///
/// Triggered by launching the app with `--earshot-bench`; see `EarshotSampleApp`.
@MainActor
enum BenchRunner {

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

        // Warm up: load + compile the CoreML model once (untimed) so the measured load below
        // reflects a cold start from cache, not the one-time network download + specialization.
        let warmup = WhisperKitTranscriptionProvider(modelName: "tiny.en")
        _ = try await warmup.ensureModelDownloaded()

        // Timed cold load on a fresh provider against the now-cached model.
        let provider = WhisperKitTranscriptionProvider(modelName: "tiny.en")
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
            "runtime": "WhisperKit (CoreML)",
            "platform": "iOS",
            "model": "whisper-tiny.en",
            "device": device,
            "provenance": provenance,
            "osVersion": osVersion,
            "loadMs": loadMs,
            "peakMemoryBytes": Int(peakMemory),
            "clips": results,
        ]
        let out = try JSONSerialization.data(withJSONObject: raw, options: [.prettyPrinted, .sortedKeys])
        let dest = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("raw-ios.json")
        try out.write(to: dest)
        print("EARSHOT_BENCH_OUTPUT: \(dest.path)")
    }

    /// Resident physical footprint of this process, the iOS equivalent of peak memory.
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
