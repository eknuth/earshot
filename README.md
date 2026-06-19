# Earshot

[![CI](https://github.com/eknuth/earshot/actions/workflows/ci.yml/badge.svg)](https://github.com/eknuth/earshot/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/dev.eknuth/earshot?label=Maven%20Central)](https://central.sonatype.com/artifact/dev.eknuth/earshot)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-iOS%20%7C%20Android-informational)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Inference](https://img.shields.io/badge/inference-100%25%20on--device-success)

On-device speech-to-text for iOS and Android, from one Kotlin core.

**Docs and quickstart: [eknuth.github.io/earshot](https://eknuth.github.io/earshot/)**

Hand Earshot an audio or video file and it gives you back the transcript, running
entirely on the phone. No server, no per-user cost, no audio leaving the device. The
only network call in the whole pipeline is the one-time model download on first run.

Earshot is the transcription engine extracted out of a working app and cleaned up
into a reusable Kotlin Multiplatform library with a small, honest API.

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

## Install

### Android (Maven Central)

```kotlin
// build.gradle.kts
dependencies {
    implementation("dev.eknuth:earshot:0.2.0")
}
```

### iOS (Swift Package Manager)

In Xcode: **File → Add Package Dependencies**, enter `https://github.com/eknuth/earshot`,
and pick the latest version. Or add it to a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/eknuth/earshot", from: "0.2.0")
]
```

This vends the shared `Earshot` framework, which is the cross-platform API. The iOS ASR
runtime itself is WhisperKit, so also add
[WhisperKit](https://github.com/argmaxinc/WhisperKit) and register a provider at launch,
as shown under [iOS](#ios) below.

Maintainers: the release process (Maven Central + the SPM XCFramework) is in
[PUBLISHING.md](PUBLISHING.md).

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
_ = try await transcriber.prepare()
let result = try await transcriber.transcribeAudio(wavPath: wavPath)
```

## Measuring it

The point of on-device is that you can prove it works where it runs, so this library
exists to be measured, and the numbers come from real hardware. Scored on 25 LibriSpeech
clips, Whisper tiny.en lands at 8.38% word error on an iPad's Neural Engine (WhisperKit /
CoreML) and 8.98% on a Pixel 9a (ONNX Runtime). On the iPad it transcribes at about 0.02x
real time, roughly a minute of speech a second.

For a model that shares one runtime across both platforms, Earshot also runs NVIDIA
Parakeet-TDT-0.6b-v3 (600M params) through sherpa-onnx. On the same Pixel 9a it cuts word
error to 2.59%, more than a 3x improvement over Whisper tiny.en, and decodes faster per clip
because a transducer runs in one pass rather than Whisper's beam search. The cost is memory:
about 1.17GB peak versus 241MB. That trade, far more accurate against far heavier, is the
kind of thing this harness exists to measure on hardware you own, not on a datacenter GPU.

Word error rate is scored offline by one algorithm over identical references, so the
runtimes are comparable by construction. The harness, per-clip results, speed and memory
are in [`benchmark/`](benchmark) and rendered at
[the benchmarks page](https://eknuth.github.io/earshot/benchmarks.html); see
[`benchmark/README.md`](benchmark/README.md) to reproduce on your own device.

## Models and licenses

See [MODELS.md](MODELS.md) for each model, where it comes from, and its license.

## Samples

- [`sample-android/`](sample-android) is a minimal Compose app: pick a file, transcribe
  on-device, see the text and timing. Build it with `./gradlew :sample-android:assembleDebug`.
- [`ios-sample/`](ios-sample) is the SwiftUI equivalent, wired with XcodeGen and
  WhisperKit. See its [README](ios-sample/README.md) to build and run.

## Status

Transcription is real and working on both platforms today. Audio extraction, the
Whisper runtimes, and model download all run on-device.

## License

MIT. See [LICENSE](LICENSE). The bundled integration code is MIT; the speech models it
loads carry their own licenses (see [MODELS.md](MODELS.md)).
