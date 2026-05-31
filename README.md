# Earshot

On-device speech-to-text for iOS and Android, from one Kotlin core.

Hand Earshot an audio or video file and it gives you back the transcript, running
entirely on the phone. No server, no per-user cost, no audio leaving the device. The
only network call in the whole pipeline is the one-time model download on first run.

Earshot is the transcription engine extracted out of a working app
([Sitewinder](https://github.com/oregonknuths)), cleaned up into a reusable Kotlin
Multiplatform library with a small, honest API.

## What this is, and what it is not

I did not train a speech model. Whisper is OpenAI's. On iOS the Whisper runtime is
[WhisperKit](https://github.com/argmaxinc/WhisperKit) by Argmax. On Android the model
is a [Microsoft Olive](https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/whisper/local/android)
export of Whisper running on ONNX Runtime.

Earshot is the part around the model: getting it onto the device, extracting clean
16kHz audio from whatever file you started with, running the model inside a phone's
memory and battery budget, and exposing one API that behaves the same on both
platforms. That integration layer is the hard, unglamorous part of shipping a model
onto someone's phone, and it is the part this library is about.

## How it works

Same model family, two on-device runtimes, one shared Kotlin core orchestrating both.

| Stage | iOS | Android |
| --- | --- | --- |
| Audio extraction | AVFoundation (`AVAssetReader`) | `MediaExtractor` + `MediaCodec` |
| ASR runtime | WhisperKit (CoreML) | ONNX Runtime + Extensions |
| Model | Whisper (CoreML, fetched + cached by WhisperKit) | Whisper (Olive ONNX, int8) |
| Model download | WhisperKit, internal | `ModelDownloader` (plain HTTPS) |

The cross-platform contract lives in `commonMain` as `expect` classes
(`AudioExtractor`, `TranscriptionEngine`, `ModelDownloader`) with platform `actual`
implementations. `OnDeviceTranscriber` is a thin facade that wires the extractor and
engine together.

## API

```kotlin
class OnDeviceTranscriber(engine: TranscriptionEngine, audioExtractor: AudioExtractor) {
    suspend fun prepare(config: TranscriptionConfig = TranscriptionConfig()): Boolean
    fun isReady(): Boolean
    fun modelStatus(): ModelStatus
    suspend fun transcribeAudio(wavPath: String): TranscriptionEngineResult
    suspend fun transcribeMedia(mediaPath: String, scratchWavPath: String): TranscriptionEngineResult
    fun release()
}
```

`TranscriptionEngineResult` is a sealed `Success(text, language, confidence, processingTimeMs)`
or `Error(message, cause)`.

### Android

```kotlin
val modelsDir = File(context.filesDir, "models")
val downloader = ModelDownloader(modelsDir)
downloader.downloadModelSync(WhisperModels.WHISPER_TINY_EN)

val engine = TranscriptionEngine().apply {
    setModelPath(File(modelsDir, WhisperModels.WHISPER_TINY_EN.name).absolutePath)
}
val transcriber = OnDeviceTranscriber(engine, AudioExtractor())
transcriber.prepare()

when (val r = transcriber.transcribeMedia(videoPath, "${context.cacheDir}/clip.wav")) {
    is TranscriptionEngineResult.Success -> println("${r.text} (${r.processingTimeMs}ms)")
    is TranscriptionEngineResult.Error -> println("failed: ${r.message}")
}
```

### iOS

Register the WhisperKit provider once at launch, then use the same shared API. The
reference Swift glue is in [`ios-support/WhisperKitTranscriptionProvider.swift`](ios-support/WhisperKitTranscriptionProvider.swift);
add WhisperKit via Swift Package Manager.

```swift
// at launch
NativeTranscriptionProviderHolder.shared.implementation = WhisperKitTranscriptionProvider()

// anywhere after
let transcriber = OnDeviceTranscriber(engine: TranscriptionEngine(),
                                      audioExtractor: AudioExtractor())
_ = try await transcriber.prepare(config: TranscriptionConfig())
let result = try await transcriber.transcribeAudio(wavPath: wavPath)
```

## Measuring it

The point of on-device is that you can prove it works where it runs, so this library
exists to be measured. On a standard word-error-rate benchmark the smaller Whisper
checkpoint lands around 12.16% word error and the next size up around 9.57%, a 21%
relative cut, at almost no latency cost. On the same benchmark it comes out ahead of
Apple's built-in on-device recognizer.

One caveat worth stating: the checkpoint you benchmark in a lab and the quantized
build that actually runs on the phone are not identical, so the number that counts is
the one you get from scoring the on-device model on a real device. Measure where the
software runs, not where it is convenient.

## Models and licenses

See [MODELS.md](MODELS.md) for each model, where it comes from, and its license.

## Status

Transcription is real and working on both platforms today. Audio extraction, the
Whisper runtimes, and model download all run on-device. Sample apps for each platform
live alongside this library.

## License

MIT. See [LICENSE](LICENSE). The bundled integration code is MIT; the speech models it
loads carry their own licenses (see [MODELS.md](MODELS.md)).
