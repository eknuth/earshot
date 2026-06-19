package dev.eknuth.earshot.sample

import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.eknuth.earshot.AudioExtractor
import dev.eknuth.earshot.NativeAsrProviderHolder
import dev.eknuth.earshot.OnDeviceTranscriber
import dev.eknuth.earshot.SherpaModels
import dev.eknuth.earshot.TranscriptionConfig
import dev.eknuth.earshot.TranscriptionEngine
import dev.eknuth.earshot.TranscriptionEngineResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device benchmark for NVIDIA Nemotron-Speech-Streaming-En-0.6b via sherpa-onnx. Same
 * shape as [ParakeetBenchmarkRunnerTest], so the :benchmark scorer judges both identically;
 * only the model, provider (streaming `OnlineRecognizer`) and output file differ.
 *
 * The model is ~632MB, distributed by k2-fsa as a tar.bz2, so it is NOT downloaded by the
 * test. Stage it once before running:
 *
 *   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25.tar.bz2
 *   tar xf sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25.tar.bz2
 *   adb shell mkdir -p /sdcard/Android/data/dev.eknuth.earshot.sample/files/models/nemotron-speech-streaming-en-0.6b
 *   adb push sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} \
 *     /sdcard/Android/data/dev.eknuth.earshot.sample/files/models/nemotron-speech-streaming-en-0.6b/
 *
 * Then run:
 *   ./gradlew :sample-android:connectedDebugAndroidTest
 *   adb pull /sdcard/Android/data/dev.eknuth.earshot.sample/files/raw-nemotron-android.json benchmark/
 *
 * On devices whose scoped-storage isolation hides shell-pushed Android/data files from the
 * app uid, stage into internal storage instead (app-readable, survives across runs that do
 * not uninstall): push the four files to /data/local/tmp, then as the app uid copy them in:
 *   adb shell run-as dev.eknuth.earshot.sample sh -c \
 *     'mkdir -p files/models/nemotron-speech-streaming-en-0.6b && \
 *      cp /data/local/tmp/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} \
 *         files/models/nemotron-speech-streaming-en-0.6b/'
 * Run via `adb shell am instrument` (connectedDebugAndroidTest uninstalls and wipes output),
 * then pull with: adb shell run-as dev.eknuth.earshot.sample cat files/raw-nemotron-android.json
 */
@RunWith(AndroidJUnit4::class)
class NemotronBenchmarkRunnerTest {

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

        // 2. The Nemotron model must already be staged (see the class doc). Fail loudly if not.
        // Accept either external-files staging (adb push, the documented path) or internal-files
        // staging (run-as cp), since some devices' scoped-storage isolation hides shell-pushed
        // files under Android/data from the app uid; internal files/ is always app-readable.
        val model = SherpaModels.NEMOTRON_STREAMING_EN_0_6B
        val outRoot = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val modelDir = listOfNotNull(appContext.getExternalFilesDir(null), appContext.filesDir)
            .map { File(File(it, "models"), model.name) }
            .firstOrNull { File(it, "encoder.int8.onnx").exists() }
            ?: error(
                "Nemotron model not staged. Push to " +
                    "${File(File(outRoot, "models"), model.name).absolutePath} (adb push), or stage into " +
                    "internal files/models/${model.name} via run-as (see class doc)."
            )

        // 3. Register the streaming sherpa provider, cold-load the engine once, timing it.
        NativeAsrProviderHolder.implementation = SherpaOnnxStreamingProvider()
        val engine = TranscriptionEngine().apply { setModelPath(modelDir.absolutePath) }
        val transcriber = OnDeviceTranscriber(engine, AudioExtractor())
        val loadStart = System.nanoTime()
        check(transcriber.prepare(TranscriptionConfig(modelName = model.name))) { "engine failed to initialize" }
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        // 4. Transcribe each clip, recording hypothesis + processing time. Sample peak memory.
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

        // 5. Write the raw run for adb to pull.
        val isEmulator = Build.FINGERPRINT.contains("generic", true) ||
            Build.PRODUCT.contains("sdk", true) || Build.MODEL.contains("sdk_gphone", true)
        val raw = JSONObject()
            .put("runtime", "sherpa-onnx")
            .put("platform", "Android")
            .put("model", "nemotron-speech-streaming-en-0.6b (int8)")
            .put("device", Build.MODEL)
            .put("provenance", if (isEmulator) "emulator" else "real-device")
            .put("osVersion", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            .put("loadMs", loadMs)
            .put("peakMemoryBytes", peakMemoryBytes)
            .put("clips", results)

        File(outRoot, "raw-nemotron-android.json").writeText(raw.toString(2))
        File(appContext.filesDir, "raw-nemotron-android.json").writeText(raw.toString(2))
    }
}
