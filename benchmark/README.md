# Earshot benchmarks

On-device, cross-runtime measurement for Earshot. The same Whisper `tiny.en` model family is
run on the same speech clips through each platform's on-device runtime, and word error rate is
scored offline by one algorithm so the runtimes are comparable by construction.

- **Android:** ONNX Runtime (Microsoft Olive int8 export)
- **iOS:** WhisperKit / CoreML

Results are rendered at <https://eknuth.github.io/earshot/benchmarks.html>.

## What's here

| Path | Role |
| --- | --- |
| `fixtures/` | 25 LibriSpeech `validation-clean` clips (16 kHz mono WAV) + `manifest.json` with ground-truth references. |
| `src/main/kotlin/.../Wer.kt` | Word error rate: normalize, word-level Levenshtein, corpus aggregation. Unit tested. |
| `src/main/kotlin/.../Scorer.kt` | Merges each runner's raw transcripts against the references and writes `results.json`. |
| `src/test/kotlin/.../WerTest.kt` | WER unit tests (`./gradlew :benchmark:test`). |
| `raw-android.json`, `raw-ios.json` | The raw on-device captures that produced the published results. |

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

This module is a developer tool. It is not part of the published Earshot library.
