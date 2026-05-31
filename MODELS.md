# Models and licenses

Earshot's own code is MIT. The speech models and runtimes it loads are third-party and
carry their own licenses. Earshot does not redistribute model weights; they are fetched
on-device at runtime from the sources below.

| Component | Role | Source | License |
| --- | --- | --- | --- |
| Whisper | Speech-to-text model | [openai/whisper](https://github.com/openai/whisper) | MIT |
| WhisperKit | iOS CoreML runtime for Whisper | [argmaxinc/WhisperKit](https://github.com/argmaxinc/WhisperKit) | MIT |
| Whisper (Olive ONNX export) | Android model artifact | [microsoft/onnxruntime-inference-examples](https://github.com/microsoft/onnxruntime-inference-examples/tree/main/mobile/examples/whisper/local/android) | MIT |
| ONNX Runtime + Extensions | Android inference runtime | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT / Apache-2.0 |

If you swap in a different Whisper export on Android, keep the same all-in-one Olive
graph I/O contract the engine binds to (see `WhisperModels` in `ModelDownloader.kt`),
and confirm that model's own license allows your use.
