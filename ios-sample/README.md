# Earshot iOS sample

A minimal SwiftUI app: pick an audio or video file, transcribe it on-device through
WhisperKit, show the text and how long it took.

## Build and run

Requires Xcode 15+, [XcodeGen](https://github.com/yonaskolb/XcodeGen), and a JDK 17 on
PATH (the pre-build step compiles the Kotlin framework).

```bash
cd ios-sample
xcodegen generate          # writes EarshotSample.xcodeproj from project.yml
open EarshotSample.xcodeproj
```

Then pick an iOS Simulator and run. On build, a pre-build script runs
`./gradlew :earshot:embedAndSignAppleFrameworkForXcode`, which compiles the Earshot
Kotlin framework and drops `Earshot.framework` where Xcode links it. WhisperKit is
pulled in over Swift Package Manager (first resolve needs network).

On first transcription WhisperKit downloads and caches its CoreML Whisper model; after
that it runs offline.

## How it wires together

- `Sources/EarshotSampleApp.swift` registers `WhisperKitTranscriptionProvider` with the
  shared `NativeTranscriptionProviderHolder` at launch, then drives
  `OnDeviceTranscriber` exactly like the Android sample does.
- `../ios-support/WhisperKitTranscriptionProvider.swift` is the reference glue between
  the shared Kotlin `NativeTranscriptionProvider` seam and WhisperKit. It is compiled
  into this sample directly (see `project.yml` sources).

The Kotlin sealed `TranscriptionEngineResult` surfaces in Swift as the nested
`TranscriptionEngineResult.Success` / `TranscriptionEngineResult.Error`; the sample
type-checks with `as?`.
