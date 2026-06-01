package dev.eknuth.earshot.sample

import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eknuth.earshot.AudioExtractor
import dev.eknuth.earshot.ModelDownloadResult
import dev.eknuth.earshot.ModelDownloader
import dev.eknuth.earshot.OnDeviceTranscriber
import dev.eknuth.earshot.TranscriptionEngine
import dev.eknuth.earshot.TranscriptionEngineResult
import dev.eknuth.earshot.WhisperModels
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device benchmark for the Android (ONNX Runtime) path. Not a pass/fail unit test: it runs the
 * fixture clips through the real Earshot pipeline, captures the raw transcripts plus load time,
 * per-clip processing time and peak memory, and writes raw-android.json. The :benchmark scorer
 * turns that into word error rate offline so both runtimes are judged identically.
 *
 * Run it with:
 *   ./gradlew :sample-android:connectedDebugAndroidTest
 * then pull the JSON:
 *   adb shell run-as dev.eknuth.earshot.sample cat files/raw-android.json   (or external files dir)
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkRunnerTest {

    @Test
    fun runBenchmark() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val testAssets = instrumentation.context.assets

        // 1. Stage fixtures from the test APK assets onto the device filesystem.
        val manifest = JSONObject(testAssets.open("fixtures/manifest.json").bufferedReader().readText())
        val clips = manifest.getJSONArray("clips")
        val fixturesDir = File(appContext.cacheDir, "bench_fixtures").apply { mkdirs() }
        for (i in 0 until clips.length()) {
            val rel = clips.getJSONObject(i).getString("wav")
            val dest = File(fixturesDir, rel)
            dest.parentFile?.mkdirs()
            testAssets.open("fixtures/$rel").use { ins -> dest.outputStream().use { ins.copyTo(it) } }
        }

        // 2. Make sure the Whisper tiny ONNX model is present (one-time download).
        val modelsDir = File(appContext.filesDir, "models")
        val downloader = ModelDownloader(modelsDir)
        val model = WhisperModels.WHISPER_TINY_EN
        if (!downloader.isModelDownloaded(model.name)) {
            val dl = downloader.downloadModelSync(model)
            check(dl !is ModelDownloadResult.Error) { "model download failed: ${(dl as ModelDownloadResult.Error).message}" }
        }
        val modelPath = downloader.getModelPath(model.name) ?: error("model path unavailable")

        // 3. Cold-load the engine once, timing it; reuse it across clips like a real app would.
        val engine = TranscriptionEngine().apply { setModelPath(modelPath) }
        val transcriber = OnDeviceTranscriber(engine, AudioExtractor())
        val loadStart = System.nanoTime()
        check(transcriber.prepare()) { "engine failed to initialize" }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        // 4. Transcribe each clip, recording the hypothesis and processing time. Sample peak memory.
        var peakMemoryBytes = 0L
        val results = JSONArray()
        for (i in 0 until clips.length()) {
            val clip = clips.getJSONObject(i)
            val id = clip.getString("id")
            val wav = File(fixturesDir, clip.getString("wav"))
            when (val r = transcriber.transcribeAudio(wav.absolutePath)) {
                is TranscriptionEngineResult.Success ->
                    results.put(JSONObject().put("id", id).put("hypothesis", r.text).put("processingMs", r.processingTimeMs))
                is TranscriptionEngineResult.Error ->
                    results.put(JSONObject().put("id", id).put("hypothesis", "").put("processingMs", 0))
            }
            peakMemoryBytes = maxOf(peakMemoryBytes, Debug.getPss() * 1024L)
        }
        transcriber.release()

        // 5. Write the raw run to the app's external files dir for adb to pull.
        val isEmulator = Build.FINGERPRINT.contains("generic", true) ||
            Build.PRODUCT.contains("sdk", true) || Build.MODEL.contains("sdk_gphone", true)
        val raw = JSONObject()
            .put("runtime", "ONNX Runtime")
            .put("platform", "Android")
            .put("model", "whisper-tiny.en (Olive int8)")
            .put("device", Build.MODEL)
            .put("provenance", if (isEmulator) "emulator" else "real-device")
            .put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("loadMs", loadMs)
            .put("peakMemoryBytes", peakMemoryBytes)
            .put("clips", results)

        val outDir = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        File(outDir, "raw-android.json").writeText(raw.toString(2))
        // Also drop a copy in the private files dir as a run-as fallback.
        File(appContext.filesDir, "raw-android.json").writeText(raw.toString(2))
    }
}
