package dev.eknuth.earshot.sample

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import dev.eknuth.earshot.NativeAsrProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * sherpa-onnx-backed [NativeAsrProvider] for the NVIDIA streaming models
 * (Nemotron-Speech-Streaming). The streaming counterpart to [SherpaOnnxProvider]:
 * Nemotron is a cache-aware streaming FastConformer + RNNT, so it runs through sherpa's
 * `OnlineRecognizer`/`OnlineStream` rather than the offline recognizer Parakeet uses.
 *
 * The benchmark harness is clip-at-a-time, so this feeds the whole WAV into one online
 * stream, signals `inputFinished`, drains the decode loop, and returns the final text.
 * The model is still streaming under the hood (it sees the audio in cache-aware chunks);
 * we just present a full utterance so the WER is comparable to the offline runs.
 *
 * Expects a model directory containing `encoder.int8.onnx`, `decoder.int8.onnx`,
 * `joiner.int8.onnx`, and `tokens.txt` (the layout of
 * sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8). The native libraries
 * `libsherpa-onnx-jni.so` + `libonnxruntime.so` must be present in jniLibs.
 */
class SherpaOnnxStreamingProvider(
    private val numThreads: Int = 4,
) : NativeAsrProvider {

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    override suspend fun loadModel(modelDir: String): Boolean = withContext(Dispatchers.IO) {
        val encoder = File(modelDir, "encoder.int8.onnx")
        val decoder = File(modelDir, "decoder.int8.onnx")
        val joiner = File(modelDir, "joiner.int8.onnx")
        val tokens = File(modelDir, "tokens.txt")
        if (!encoder.exists() || !decoder.exists() || !joiner.exists() || !tokens.exists()) {
            return@withContext false
        }

        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                numThreads = numThreads,
                provider = "cpu",
                // No modelType: let sherpa auto-detect the NeMo streaming transducer path from
                // the encoder metadata, the same reason the offline Parakeet provider leaves it
                // unset. The Nemotron streaming export advertises its model type in the encoder.
            ),
            // Endpointing off: the harness feeds a single bounded clip and reads one final
            // result, so utterance segmentation would only fragment short clips.
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        )
        recognizer = OnlineRecognizer(config = config)
        true
    }

    override suspend fun transcribe(audioPath: String): String = withContext(Dispatchers.Default) {
        val r = recognizer ?: error("sherpa online recognizer not loaded")
        val wave = WaveReader.readWave(audioPath)
        val stream = r.createStream()
        stream.acceptWaveform(wave.samples, wave.sampleRate)
        // Flush: tell sherpa no more audio is coming so it can pad/finish the last chunk.
        stream.inputFinished()
        // Drain the cache-aware decode loop until the stream has consumed all buffered frames.
        while (r.isReady(stream)) {
            r.decode(stream)
        }
        val text = r.getResult(stream).text
        stream.release()
        text.trim()
    }

    override fun isReady(): Boolean = recognizer != null

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}
