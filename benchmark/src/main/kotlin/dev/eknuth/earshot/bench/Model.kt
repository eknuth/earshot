package dev.eknuth.earshot.bench

import kotlinx.serialization.Serializable

// ---- Fixture manifest (written by the fixture extractor, references are ground truth) ----

@Serializable
data class Manifest(
    val dataset: String,
    val sampleRate: Int,
    val channels: Int,
    val count: Int,
    val totalAudioSec: Double,
    val clips: List<ManifestClip>,
)

@Serializable
data class ManifestClip(
    val id: String,
    val wav: String,
    val reference: String,
    val durationSec: Double,
)

// ---- Raw capture (written by each on-device runner: hypotheses + timings only, no scoring) ----

@Serializable
data class RawRun(
    val runtime: String,        // e.g. "ONNX Runtime", "WhisperKit (CoreML)"
    val platform: String,       // "Android" | "iOS"
    val model: String,          // e.g. "whisper-tiny.en"
    val device: String,         // model identifier or simulator/emulator name
    val provenance: String,     // "real-device" | "simulator" | "emulator"
    val osVersion: String,
    val loadMs: Long,           // cold model load time
    val peakMemoryBytes: Long,  // peak process memory observed during the run
    val clips: List<RawClip>,
)

@Serializable
data class RawClip(
    val id: String,
    val hypothesis: String,
    val processingMs: Long,
)

// ---- Scored report (written by the Scorer, consumed by the docs visual) ----

@Serializable
data class ScoredReport(
    val note: String,
    val dataset: String,
    val fixtureCount: Int,
    val totalAudioSec: Double,
    val runtimes: List<RuntimeSummary>,
)

@Serializable
data class RuntimeSummary(
    val runtime: String,
    val platform: String,
    val model: String,
    val device: String,
    val provenance: String,
    val osVersion: String,
    val werPercent: Double,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val referenceWords: Int,
    val loadMs: Long,
    val peakMemoryBytes: Long,
    val medianRtf: Double,   // realtime factor: processing time / audio duration (lower is faster)
    val meanRtf: Double,
    val clips: List<ScoredClip>,
)

@Serializable
data class ScoredClip(
    val id: String,
    val werPercent: Double,
    val rtf: Double,
    val processingMs: Long,
    val audioMs: Long,
    val referenceWords: Int,
)
