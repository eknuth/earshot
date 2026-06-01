# Contributing to Earshot

Thanks for your interest in improving Earshot. This guide covers how to build the
library and both sample apps, run the checks, and the conventions the project follows.

Earshot is a Kotlin Multiplatform library that runs speech-to-text entirely on the
device. The shared core lives in `commonMain`, with platform code in `androidMain` and
`iosMain`. Keep that split in mind when proposing changes: the public API should behave
the same on both platforms.

## Prerequisites

- JDK 17 (Temurin or any distribution).
- The Gradle wrapper is checked in, so you do not need a system Gradle. The project
  builds with Gradle 8.7.
- For iOS work: a macOS machine with Xcode, plus [XcodeGen](https://github.com/yonaskolb/XcodeGen)
  for the SwiftUI sample.

## Project layout

- `:earshot` is the Kotlin Multiplatform library with Android and iOS targets.
- `:sample-android` is a minimal Compose app that exercises the library on Android.
- `ios-sample/` is the SwiftUI equivalent, wired with XcodeGen and WhisperKit.

## Building

Build the library:

```bash
./gradlew :earshot:assemble
```

Build the Android sample:

```bash
./gradlew :sample-android:assembleDebug
```

Build the Kotlin iOS framework (the binary the Swift side links against):

```bash
./gradlew :earshot:linkDebugFrameworkIosSimulatorArm64
```

Run the unit tests:

```bash
./gradlew :earshot:testDebugUnitTest
```

## Running the samples

### Android

Install the debug build on a connected device or emulator:

```bash
./gradlew :sample-android:installDebug
```

Then launch the app, pick an audio or video file, and watch it transcribe on-device.
The first run downloads the Whisper model over HTTPS and caches it locally.

### iOS

The SwiftUI sample is generated with XcodeGen. From `ios-sample/`, run `xcodegen`, open
the generated project in Xcode, and run it on a simulator or device. See
[`ios-sample/README.md`](ios-sample/README.md) for the full steps. WhisperKit is added
through Swift Package Manager.

## Code style

- Follow the Kotlin official code style: 4-space indentation, no tabs, a 120-column
  soft limit. The settings live in [`.editorconfig`](.editorconfig).
- Keep the shared API symmetric across platforms. If you add a capability on one side,
  add or stub the matching `expect`/`actual` on the other.
- Prefer small, focused commits with clear messages.
- Do not add code that sends audio, model data, or user content off the device. The
  only network call in the pipeline is the one-time model download.

## Submitting a change

1. Fork the repository and create a branch.
2. Make your change and run the relevant builds and tests above.
3. Open a pull request and fill out the template. Link any related issue.

CI runs wrapper validation, the Android build and tests, and the iOS framework build on
every pull request. Please make sure those pass.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating
you agree to uphold it.
