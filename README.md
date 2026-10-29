# Haku

On-device search assistant for Android. Gemma 4 runs locally on the phone (LiteRT-LM, GPU),
decides by itself when to search, reads pages and answers with sources. No account, no cloud LLM.

## Install

1. Install [Obtainium](https://github.com/ImranR98/Obtainium), add `https://github.com/Lepune/haku`.
2. Open Haku → ⚙ → download **Gemma 4 E4B** (~3.7 GB, once). It loads automatically when done.

Every push to `main` builds a signed APK and publishes it as a GitHub release, so Obtainium picks up updates.

## What's inside

| Part | Choice |
|---|---|
| Runtime | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) Kotlin API, automatic tool calling |
| Models | Gemma 4 E4B (default), Gemma 4 E2B (fast), Gemma 3 1B Tensor G4 NPU build (NPU test only) |
| Accelerator | GPU (OpenCL) → CPU fallback. NPU selectable; only the G4 NPU test model targets it |
| Tools | `webSearch` (DuckDuckGo, or your SearXNG), `wikiSearch` (fi/en Wikipedia API), `readPage` |
| UI | Jetpack Compose, Material You, markdown answers, source chips, tok/s stats |

The engine status line in the top bar shows which backend actually loaded, so you can see whether
the NPU or GPU is in use.

## Own server later

Set **SearXNG URL** in settings (e.g. your Tailscale address). Web search then goes there instead
of DuckDuckGo. Offline Wikipedia (kiwix-serve) is planned; see `SPEC.md`.

## Build locally

```
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK (compileSdk 36).
