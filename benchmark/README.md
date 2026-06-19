# Earshot benchmarks

On-device, cross-runtime measurement for Earshot. The same model is run on the same speech
clips through each platform's on-device runtime, and word error rate is scored offline by one
algorithm so the runtimes are comparable by construction.

Whisper `tiny.en` (~39M params):
- **Android:** ONNX Runtime (Microsoft Olive int8 export)
- **iOS:** WhisperKit / CoreML

NVIDIA Parakeet-TDT-0.6b-v3 (600M params), one runtime on both platforms:
- **Android + iOS:** sherpa-onnx (int8)

The point of carrying both is the scale gap: a 600M leaderboard model against a 39M model
that already fits, measured for word error, real-time factor, and peak memory on hardware you
own, not on a datacenter GPU.

Results are rendered at <https://eknuth.github.io/earshot/benchmarks.html>.

## What's here

| Path | Role |
| --- | --- |
| `fixtures/` | 25 LibriSpeech `validation-clean` clips (16 kHz mono WAV) + `manifest.json` with ground-truth references. |
| `src/main/kotlin/.../Wer.kt` | Word error rate: normalize, word-level Levenshtein, corpus aggregation. Unit tested. |
| `src/main/kotlin/.../Scorer.kt` | Merges each runner's raw transcripts against the references and writes `results.json`. |
| `src/test/kotlin/.../WerTest.kt` | WER unit tests (`./gradlew :benchmark:test`). |
| `raw-android.json`, `raw-ios.json` | The raw Whisper on-device captures that produced the published results. |
| `raw-parakeet-android.json`, `raw-parakeet-ios.json` | The raw Parakeet (sherpa-onnx, offline) captures, once run on-device. |
| `raw-nemotron-android.json`, `raw-nemotron-ios.json` | The raw Nemotron-Speech-Streaming (sherpa-onnx, streaming) captures, once run on-device. |

Each on-device runner only records raw hypotheses plus load time, per-clip processing time and
peak memory. Scoring happens here, once, so both runtimes are judged by identical math. Accuracy
is comparable across platforms; **RTF and peak memory are only representative when a run's
`provenance` is `real-device`** (a simulator or emulator does not use the phone's Neural Engine).

## Reproduce

The on-device runners live in the sample apps.

**Android** (connected device or emulator):

```sh
./gradlew :sample-android:assembleDebug :sample-android:assembleDebugAndroidTest
adb install -r -g sample-android/build/outputs/apk/debug/*.apk
adb install -r -g sample-android/build/outputs/apk/androidTest/debug/*.apk
adb shell am instrument -w -e class dev.eknuth.earshot.sample.BenchmarkRunnerTest \
  dev.eknuth.earshot.sample.test/androidx.test.runner.AndroidJUnitRunner
adb exec-out run-as dev.eknuth.earshot.sample cat files/raw-android.json > benchmark/raw-android.json
```

**iOS** (connected device or simulator):

```sh
# build + install the sample (ios-sample), then:
xcrun simctl launch --console-pty booted dev.eknuth.earshot.sample --earshot-bench   # simulator
# or, on a device, launch with devicectl passing --earshot-bench, then copy
#   Documents/raw-ios.json out of the app container.
```

**Score both:**

```sh
./gradlew :benchmark:run --args="--manifest benchmark/fixtures/manifest.json \
  --raw benchmark/raw-android.json --raw benchmark/raw-ios.json \
  --out docs/benchmarks/results.json"
```

## Parakeet (sherpa-onnx) reproduction

The NVIDIA models run through sherpa-onnx, which is supplied by the host app, not the
published library. Two extra one-time setup steps are needed: the native libraries and the
~640MB model.

**Android.** sherpa-onnx has no Maven artifact, so its native libraries are not vendored in
git. Download a sherpa-onnx Android release, then drop the arm64-v8a `.so` files into
`sample-android/src/main/jniLibs/arm64-v8a/`:

```sh
# native libs (libsherpa-onnx-jni.so, libonnxruntime.so) for arm64-v8a
#   from https://github.com/k2-fsa/sherpa-onnx/releases (sherpa-onnx-vX.Y.Z-android)
# then stage the model on the device:
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2
tar xf sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2
M=/sdcard/Android/data/dev.eknuth.earshot.sample/files/models/parakeet-tdt-0.6b-v3-int8
adb shell mkdir -p "$M"
adb push sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} "$M"/

./gradlew :sample-android:assembleDebug :sample-android:assembleDebugAndroidTest
adb install -r -g sample-android/build/outputs/apk/debug/*.apk
adb install -r -g sample-android/build/outputs/apk/androidTest/debug/*.apk
adb shell am instrument -w -e class dev.eknuth.earshot.sample.ParakeetBenchmarkRunnerTest \
  dev.eknuth.earshot.sample.test/androidx.test.runner.AndroidJUnitRunner
adb pull "$(dirname $M)/../raw-parakeet-android.json" benchmark/ 2>/dev/null || \
  adb exec-out run-as dev.eknuth.earshot.sample cat files/raw-parakeet-android.json > benchmark/raw-parakeet-android.json
```

**iOS.** Add the sherpa-onnx Swift package (github.com/k2-fsa/sherpa-onnx, 1.10.0+) and the
`SherpaOnnx.swift` helper + bridging header to the `ios-sample` target, add the extracted
model directory as a folder reference named `parakeet`, then launch with
`--earshot-bench-parakeet` and copy `Documents/raw-parakeet-ios.json` out of the app container.

## Nemotron streaming (sherpa-onnx) reproduction

Nemotron-Speech-Streaming-En-0.6b is the streaming sibling of Parakeet: a cache-aware
FastConformer + RNNT, so it runs through sherpa's `OnlineRecognizer`/`OnlineStream` rather
than the offline recognizer. The benchmarked export is the 1120ms-chunk int8 variant
(best accuracy of the chunk sizes). Setup mirrors Parakeet; the native libraries are shared.

**Android.** Reuse the same jniLibs, then stage the streaming model and run the Nemotron test:

```sh
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25.tar.bz2
tar xf sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25.tar.bz2
M=/sdcard/Android/data/dev.eknuth.earshot.sample/files/models/nemotron-speech-streaming-en-0.6b
adb shell mkdir -p "$M"
adb push sherpa-onnx-nemotron-speech-streaming-en-0.6b-1120ms-int8-2026-04-25/{encoder.int8.onnx,decoder.int8.onnx,joiner.int8.onnx,tokens.txt} "$M"/

./gradlew :sample-android:assembleDebug :sample-android:assembleDebugAndroidTest
adb install -r -g sample-android/build/outputs/apk/debug/*.apk
adb install -r -g sample-android/build/outputs/apk/androidTest/debug/*.apk
adb shell am instrument -w -e class dev.eknuth.earshot.sample.NemotronBenchmarkRunnerTest \
  dev.eknuth.earshot.sample.test/androidx.test.runner.AndroidJUnitRunner
adb pull "$(dirname $M)/../raw-nemotron-android.json" benchmark/ 2>/dev/null || \
  adb exec-out run-as dev.eknuth.earshot.sample cat files/raw-nemotron-android.json > benchmark/raw-nemotron-android.json
```

**iOS.** Same sherpa-onnx package/helper/bridging header as Parakeet (the online classes are
already in `SherpaOnnx.swift`). Add the extracted model directory as a folder reference named
`nemotron`, then launch with `--earshot-bench-nemotron` and copy `Documents/raw-nemotron-ios.json`
out of the app container.

**Re-score with all raw files (use absolute paths; the Gradle working dir is the module dir):**

```sh
R="$PWD/benchmark"
./gradlew :benchmark:run --args="--manifest $R/fixtures/manifest.json \
  --raw $R/raw-android.json --raw $R/raw-ios.json --raw $R/raw-apple.json \
  --raw $R/raw-parakeet-android.json --raw $R/raw-parakeet-ios.json \
  --raw $R/raw-nemotron-android.json --raw $R/raw-nemotron-ios.json \
  --out $PWD/docs/benchmarks/results.json"
```

This module is a developer tool. It is not part of the published Earshot library.
