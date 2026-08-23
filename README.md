# WaveCut Audio Editor

WaveCut is an offline-first Android audio editor with a focused mobile UI.

## Features

- Import audio through Android's Storage Access Framework
- Waveform preview with trim handles
- Non-destructive trim start/end controls
- Gain from -24 dB to +12 dB
- Normalize, fade in, fade out, and reverse
- Preview the edited selection before export
- Export 16-bit PCM WAV through Android's system Save dialog
- No storage permission and no network permission

## Build

This repository builds with Android Gradle Plugin 8.10.1, Gradle 8.11.1, JDK 17, compile/target SDK 35, and min SDK 24.

GitHub Actions uploads the debug APK as the `WaveCut-APK` artifact.
