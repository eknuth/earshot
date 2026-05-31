package dev.knuth.earshot.sample

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.knuth.earshot.AudioExtractor
import dev.knuth.earshot.ModelDownloadResult
import dev.knuth.earshot.ModelDownloader
import dev.knuth.earshot.OnDeviceTranscriber
import dev.knuth.earshot.TranscriptionEngine
import dev.knuth.earshot.TranscriptionEngineResult
import dev.knuth.earshot.WhisperModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Smallest honest demo of the Earshot SDK on Android: pick an audio or video file,
 * transcribe it entirely on-device, show the text and how long it took.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { EarshotScreen() }
            }
        }
    }
}

@Composable
fun EarshotScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Pick an audio or video file to transcribe on-device.") }
    var transcript by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        transcript = ""
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    transcribeOnDevice(context, uri) { status = it }
                }
                when (result) {
                    is TranscriptionEngineResult.Success -> {
                        transcript = result.text
                        status = "Done on-device in ${result.processingTimeMs} ms"
                    }
                    is TranscriptionEngineResult.Error -> status = "Error: ${result.message}"
                }
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Earshot", style = MaterialTheme.typography.headlineMedium)
        Text(
            "On-device speech to text. Whisper via ONNX Runtime. Nothing leaves the phone.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = { picker.launch("*/*") }, enabled = !busy) {
            Text(if (busy) "Working..." else "Pick audio / video")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (transcript.isNotEmpty()) {
            HorizontalDivider()
            Text(transcript, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/**
 * The whole pipeline, kept deliberately literal so the SDK surface is easy to read:
 * copy the picked file local, make sure the model is present, wire the engine and
 * extractor into [OnDeviceTranscriber], transcribe.
 */
private suspend fun transcribeOnDevice(
    context: Context,
    uri: Uri,
    onStatus: (String) -> Unit,
): TranscriptionEngineResult {
    // 1. Copy the picked content into a real file path the extractor can read.
    val input = File(context.cacheDir, "input_media")
    context.contentResolver.openInputStream(uri)?.use { ins ->
        input.outputStream().use { outs -> ins.copyTo(outs) }
    } ?: return TranscriptionEngineResult.Error("Could not open the selected file")

    // 2. Make sure the Whisper model is on-device (one-time ~71MB download).
    val modelsDir = File(context.filesDir, "models")
    val downloader = ModelDownloader(modelsDir)
    val model = WhisperModels.WHISPER_TINY_EN
    if (!downloader.isModelDownloaded(model.name)) {
        onStatus("Downloading model (one time)...")
        val dl = downloader.downloadModelSync(model)
        if (dl is ModelDownloadResult.Error) {
            return TranscriptionEngineResult.Error("Model download failed: ${dl.message}")
        }
    }
    val modelPath = downloader.getModelPath(model.name)
        ?: return TranscriptionEngineResult.Error("Model path unavailable after download")

    // 3. Wire the engine + extractor into the facade and transcribe on-device.
    onStatus("Transcribing on-device...")
    val engine = TranscriptionEngine().apply { setModelPath(modelPath) }
    val transcriber = OnDeviceTranscriber(engine, AudioExtractor())
    if (!transcriber.prepare()) {
        return TranscriptionEngineResult.Error("Engine failed to initialize")
    }
    val scratchWav = File(context.cacheDir, "scratch.wav")
    val result = transcriber.transcribeMedia(input.absolutePath, scratchWav.absolutePath)
    transcriber.release()
    return result
}
