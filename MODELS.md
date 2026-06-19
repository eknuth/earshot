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
| NVIDIA Parakeet-TDT-0.6b-v3 | Speech-to-text model (transducer) | [nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) | CC-BY-4.0 |
| NVIDIA Nemotron-Speech-Streaming-En-0.6b | Speech-to-text model (streaming) | [nvidia/nemotron-speech-streaming-en-0.6b](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b) | NVIDIA OpenMDW-1.1 |
| sherpa-onnx | Cross-platform runtime for the NVIDIA models (Android + iOS) | [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 |

If you swap in a different Whisper export on Android, keep the same all-in-one Olive
graph I/O contract the engine binds to (see `WhisperModels` in `ModelDownloader.kt`),
and confirm that model's own license allows your use.

The NVIDIA models are 600M-parameter FastConformer models (roughly 15x the size of
Whisper tiny.en) run through sherpa-onnx, which carries its own ONNX Runtime. sherpa-onnx
is supplied by the host app rather than bundled in the published library, the same way
the iOS WhisperKit runtime is, so the published artifact stays free of a second,
conflicting `libonnxruntime.so`. The model definitions and the backend seam live in
`SherpaModels` / `NativeAsrProvider` (`ModelDownloader.kt`, `TranscriptionEngine.kt`);
the reference runtime wiring is in the sample apps.

Parakeet-TDT-0.6b-v3 is an offline transducer run through sherpa's `OfflineRecognizer`.
Nemotron-Speech-Streaming-En-0.6b is a cache-aware streaming FastConformer + RNNT, so it
runs through sherpa's `OnlineRecognizer`/`OnlineStream` instead; Earshot routes it through
the `SHERPA_STREAMING` backend. The benchmarked Nemotron export is the 1120ms-chunk int8
variant from the sherpa-onnx asr-models release, which gives the best accuracy of the
chunk sizes while still being the streaming model.
