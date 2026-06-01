# Security Policy

## Reporting a vulnerability

If you find a security issue in Earshot, please report it privately. Email
**eknuth@gmail.com** with a description of the problem, the affected version or commit,
and steps to reproduce it if you have them. Please do not open a public issue for a
security report.

You can expect an acknowledgement within a few days. Once the issue is confirmed, a fix
and a coordinated disclosure timeline will be worked out with you.

## Scope

Earshot is an on-device speech-to-text library. It runs the full transcription pipeline
on the phone: audio extraction, the Whisper runtime, and inference all happen locally.
There is no Earshot server, and no audio, model data, or transcript is sent off the
device. The only network call in the pipeline is the one-time model download over
HTTPS on first run.

Because of that design, the most relevant areas for security review are:

- The model download path, including HTTPS handling and where downloaded files are
  written and validated on disk.
- Handling of untrusted input files passed to the audio extractor.
- Any code that loads or executes model artifacts.

Issues in third-party model runtimes (WhisperKit, ONNX Runtime) or in the speech models
themselves should be reported to those projects. If such an issue affects how Earshot
integrates them, a report here is still welcome.

## Supported versions

This is an early-stage project, so security fixes target the latest release and the
`main` branch. Please upgrade to the most recent version before reporting.
