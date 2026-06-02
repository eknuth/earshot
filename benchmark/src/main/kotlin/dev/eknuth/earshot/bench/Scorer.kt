package dev.eknuth.earshot.bench

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Merge the raw transcripts captured on-device against the fixture references and emit the
 * scored results.json the docs site renders.
 *
 * Usage:
 *   scorer --manifest fixtures/manifest.json --out docs/benchmarks/results.json \
 *          --raw raw-android.json --raw raw-ios.json
 *
 * Every runtime is scored by the same [wer] over the same references, so the cross-runtime
 * accuracy numbers are comparable by construction rather than by trust.
 */
private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

fun main(args: Array<String>) {
    var manifestPath: String? = null
    var outPath: String? = null
    val rawPaths = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--manifest" -> manifestPath = args[++i]
            "--out" -> outPath = args[++i]
            "--raw" -> rawPaths += args[++i]
            else -> error("unknown arg: ${args[i]}")
        }
        i++
    }
    require(manifestPath != null) { "--manifest is required" }
    require(outPath != null) { "--out is required" }
    require(rawPaths.isNotEmpty()) { "at least one --raw file is required" }

    val manifest = json.decodeFromString<Manifest>(File(manifestPath).readText())
    val refById = manifest.clips.associateBy { it.id }

    val runtimes = rawPaths.map { path ->
        val raw = json.decodeFromString<RawRun>(File(path).readText())
        scoreRun(raw, refById)
    }

    val report = ScoredReport(
        note = "WER scored offline by the same algorithm over identical references. RTF and " +
            "peak memory are only representative when provenance is real-device; simulator and " +
            "emulator figures show the pipeline runs but not its true on-device speed.",
        dataset = manifest.dataset,
        fixtureCount = manifest.count,
        totalAudioSec = manifest.totalAudioSec,
        runtimes = runtimes,
    )
    val outFile = File(outPath)
    outFile.parentFile?.mkdirs()
    outFile.writeText(json.encodeToString(report))

    println("Scored ${runtimes.size} runtime(s) over ${manifest.count} clips -> $outPath")
    runtimes.forEach { r ->
        println("  ${r.platform} / ${r.runtime} [${r.provenance}]: " +
            "WER ${"%.2f".format(r.werPercent)}%, median RTF ${"%.2f".format(r.medianRtf)}x")
    }
}

private fun scoreRun(raw: RawRun, refById: Map<String, ManifestClip>): RuntimeSummary {
    val scoredClips = raw.clips.mapNotNull { clip ->
        val ref = refById[clip.id] ?: return@mapNotNull null
        val w = wer(ref.reference, clip.hypothesis)
        val audioMs = (ref.durationSec * 1000).toLong()
        val rtf = if (audioMs > 0) clip.processingMs.toDouble() / audioMs else 0.0
        ScoredClip(
            id = clip.id,
            werPercent = w.rate * 100,
            rtf = rtf,
            processingMs = clip.processingMs,
            audioMs = audioMs,
            referenceWords = w.referenceWords,
        ) to w
    }
    val aggregate = aggregateWer(scoredClips.map { it.second })
    val rtfs = scoredClips.map { it.first.rtf }.sorted()
    return RuntimeSummary(
        runtime = raw.runtime,
        platform = raw.platform,
        model = raw.model,
        device = raw.device,
        provenance = raw.provenance,
        osVersion = raw.osVersion,
        accuracyOnly = raw.accuracyOnly,
        werPercent = aggregate.rate * 100,
        substitutions = aggregate.substitutions,
        deletions = aggregate.deletions,
        insertions = aggregate.insertions,
        referenceWords = aggregate.referenceWords,
        loadMs = raw.loadMs,
        peakMemoryBytes = raw.peakMemoryBytes,
        medianRtf = median(rtfs),
        meanRtf = if (rtfs.isEmpty()) 0.0 else rtfs.average(),
        clips = scoredClips.map { it.first },
    )
}

private fun median(sorted: List<Double>): Double = when {
    sorted.isEmpty() -> 0.0
    sorted.size % 2 == 1 -> sorted[sorted.size / 2]
    else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
}
