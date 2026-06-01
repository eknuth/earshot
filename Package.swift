// swift-tools-version:5.9
import PackageDescription

// Earshot ships its shared Kotlin Multiplatform core to Swift as a prebuilt XCFramework.
// The binary is attached to each GitHub release; the URL and checksum below are updated per
// release (the release workflow rewrites them, or set them by hand per PUBLISHING.md).
//
// This product gives you the cross-platform API (OnDeviceTranscriber, TranscriptionEngine,
// AudioExtractor, the sealed result types). On iOS the ASR runtime itself is WhisperKit:
// add https://github.com/argmaxinc/WhisperKit and register a NativeTranscriptionProvider at
// launch, as shown in ios-support/WhisperKitTranscriptionProvider.swift and the README.
let package = Package(
    name: "Earshot",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(
            name: "Earshot",
            targets: ["Earshot"]
        )
    ],
    targets: [
        .binaryTarget(
            name: "Earshot",
            url: "https://github.com/eknuth/earshot/releases/download/v0.1.0/Earshot.xcframework.zip",
            checksum: "66e76b5604a3071baff107da7351657d70de0a0710cac644f4a34725ed5954e4"
        )
    ]
)
