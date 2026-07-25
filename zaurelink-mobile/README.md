<p align="center">
  <img src="./assets/images/zaurelink-logo.png" alt="ZaureLink" width="220" />
</p>

<h1 align="center">ZaureLink</h1>
<p align="center"><strong>Offline, bidirectional Hausa ↔ English speech-to-speech translation — 100% on-device.</strong></p>

<p align="center">
  <img alt="Track" src="https://img.shields.io/badge/Track-Gemma%20for%20Local%20Languages%20%26%20Literacy-fa9923">
  <img alt="Hackathon" src="https://img.shields.io/badge/Build%20With%20Gemma-GDG%20on%20Campus%20ABU%20Zaria-1f355f">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3ddc84">
</p>

---

ZaureLink runs a full speech-to-speech translator between Hausa and English entirely on a phone's own CPU — no server, no API call, no data plan, at any point after the one-time model download. It exists for one specific, unglamorous reason: the people who most need it — market traders, Keke Napep passengers, patients describing symptoms to a chemist — are almost always the people with the least reliable connectivity, and cloud translation apps simply stop working the moment the signal does.

Built for **Build With Gemma: GDG on Campus ABU Zaria**, submitted under **Track 1 — Gemma for Local Languages & Literacy**.

## Table of Contents

- [The problem](#the-problem)
- [What it does](#what-it-does)
- [How Gemma 4 powers ZaureLink](#how-gemma-4-powers-zaurelink)
- [System architecture](#system-architecture)
- [Tech stack](#tech-stack)
- [Repository structure](#repository-structure)
- [Getting started](#getting-started)
- [Known limitations & roadmap](#known-limitations--roadmap)
- [Hackathon submission](#hackathon-submission)
- [License](#license)

## The problem

Students at Ahmadu Bello University, Samaru, Zaria cross the Hausa↔English boundary constantly — bargaining in a market stall, catching a Keke, describing a symptom to a chemist — almost always somewhere connectivity is unreliable, expensive, or absent outright. Cloud translation apps fail on the one precondition that matters most in these settings: a signal. Generic machine translation has a second, quieter failure mode even when it *is* reachable — it isn't tuned to Northern Nigerian market shorthand, currency slang, negotiation idiom, or clinical Hausa phrasing (*"Ina jin zazzabi"* is not a word-for-word sentence, it's a symptom report).

ZaureLink's answer to both failures is the same: put the model on the device, and make its prompting genuinely domain-aware instead of generically bilingual.

## What it does

- **Two domain-tuned modes** — **Market** (bargaining, currency shorthand, negotiation idiom) and **Campus** (transport fares, clinical phrasing, academic/administrative terms).
- **Genuinely bidirectional** — the app user declares their own required language once per session (Hausa or English); the other party is always the complement. There's no fixed "student speaks English, trader speaks Hausa" assumption — either person can be the Hausa speaker.
- **Never over-translates** — if a speaker already used the language their listener needs (including mid-sentence code-switching), ZaureLink relays it unchanged instead of mistranslating an already-understood utterance.
- **Privacy-first by hardware, not just policy** — an earpiece is the app user's private input/output channel; the phone's own mic/speaker is the public channel for the other party. A Bluetooth disconnect **fails closed**: the pipeline halts and mutes immediately, with an explicit visible prompt to opt back in — never a silent reroute of a private conversation to a public speaker.
- **Remembers the conversation** — a bounded sliding window of recent turns lets the model resolve "it" to the price just quoted, keep a multi-sentence symptom description coherent, and hold each speaker's tone across turns, without growing memory unbounded.
- **Built for outdoor light** — high-contrast, large-type transcript UI legible in direct Nigerian midday sun, because the market is where this app actually has to work.
- **Ships with zero model weights in the APK** — the initial install is a thin shell; the ~2.6GB Gemma 4 E2B model and the ~109MB Hausa voice model download once, post-install, with resumable/checksum-verified transfer (see [Getting started](#getting-started)).

## How Gemma 4 powers ZaureLink

This is the core of the submission, not an add-on — the entire product thesis (and the hackathon's own **Gemma Integration (30%)** and **Innovation & Impact (30%)** criteria) rests on the model actually running on-device, not behind an API.

### Model & runtime

| | |
|---|---|
| Model | `litert-community/gemma-4-E2B-it-litert-lm` (Gemma 4 E2B, int4) |
| Runtime | [LiteRT-LM](https://github.com/google-ai-edge/LiteRT-LM) via `com.google.ai.edge.litertlm:litertlm-android:0.14.0` |
| Loading | Weights are `mmap`'d from disk (not fully materialized in the JVM heap) — resident RAM during inference is dominated by the KV cache and activation buffers, not the on-disk file size |
| Backend | CPU (XNNPACK), 4 threads |
| Context / cache | `context_length: 2048`, `cache_length: 1536` — sized for a realistic 8–10 exchange conversation (~1,000–1,500 tokens including the system prompt), not the model's full context ceiling |
| Session policy | Bounded 8-turn sliding conversation window; resets on explicit end, inactivity timeout, or a Mode/Language switch — **not** on a Bluetooth disconnect/reconnect, since a dropped earpiece shouldn't erase what's already been said |

### Three-tier provider strategy

A single `TranslationProvider` interface (`modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/TranslationProvider.kt`) decouples the entire app — UI, audio pipeline, session lifecycle — from which model implementation is actually behind it:

| Provider | Backing | Purpose |
|---|---|---|
| `MockTranslationProvider` | Rule-based canned responses + language-heuristic detection, no model | Lets the full app (UI, routing, session lifecycle, TTS) be built and demoed with zero model dependency — and lets the mobile and fine-tuning workstreams run in parallel |
| `Gemma4BaselineProvider` | Stock `litert-community/gemma-4-E2B-it-litert-lm`, no fine-tuning | Real on-device inference, real latency/RAM/audio-I/O behavior, real multi-turn memory — validated before any fine-tuned artifact exists |
| `Gemma4FineTunedProvider` | LoRA-merged, quantized export via `litert-torch` | Production translation quality, trained on multi-turn examples across all four Environment × Language configurations |

Switching providers is a single config value — no other app code changes. This is what let the mobile app and the fine-tuning workstream proceed in parallel on an 11-day runway instead of serializing on each other.

### Four system prompts, not a generic bilingual instruction

`ModeConfigResolver` selects exactly one of four system prompts at session start — **Environment** (Market/Campus) × **AppUserLanguage** (Hausa/English) — and it never changes mid-session. Each prompt applies three rules in strict priority order (see `Gemma4BaselineProvider.kt` for the verbatim text):

1. **Pass-through** — if the speaker's language already matches what the listener needs, relay it unchanged. Don't translate language that doesn't need translating.
2. **Translate** — if it doesn't match, translate into the listener's required language.
3. **Mixed-utterance-as-one-unit** — if Hausa and English are mixed within a single sentence, translate the whole utterance's meaning as one unit rather than emitting a half-translated sentence.

Each of the four prompts also carries a concrete domain glossary rather than trusting the model to infer local convention unaided — Market mode resolves currency shorthand (*"dari biyar"* → 500, output with "naira"), bargaining idiom (*"farashi na karshe"* → "final price," not "my last price"), and local units of measurement; Campus mode resolves transport terms (*Keke*, *Okada*), clinical phrasing (*"jikina yana zafi"* → "my body aches"), and academic/administrative terminology (course registration, NYSC documentation, fee instructions).

### Speaker tags

Since Campus mode has no single fixed counterpart role (driver, chemist, lecturer, staff, fellow student), each turn's input is prefixed with an explicit speaker tag derived from mechanical routing state, so the model never has to guess who's speaking from content alone:

| Active channel | Environment | Tag |
|---|---|---|
| Earpod (app user) | either | `app_user:` |
| Phone mic (other party) | Market | `trader:` |
| Phone mic (other party) | Campus | `other_party:` |

Tags are lowercase snake_case so they read structurally as metadata rather than conversation content the model might try to translate.

### Why on-device, not an API call

This isn't a cost-saving shortcut — a cloud-dependent version of this app is a fundamentally different, and for this user base non-functional, product. The target hardware floor is a ≤4GB RAM, Snapdragon 4-series device; the end-to-end latency budget (speaker finishes → translation appears/plays) is ≤1.5s, enforced by per-stage timing recorded in `TranslationResult.latencyBreakdownMs` so a slow stage is diagnosable during a live demo, not just in theory.

## System architecture

```
┌──────────────┐   ┌──────────────┐   ┌────────────────┐   ┌───────────────────┐   ┌─────────────┐
│ Audio Capture │──▶│  DSP Filter  │──▶│  VAD Gatekeeper │──▶│ Translation       │──▶│ UI + TTS    │
│ (mic route)   │   │ (WebRTC NS)  │   │  (Silero VAD)   │   │ Provider          │   │ Output      │
│               │   │              │   │                 │   │ (Mock/Baseline/   │   │             │
│               │   │              │   │                 │   │  Fine-tuned)      │   │             │
└──────────────┘   └──────────────┘   └────────────────┘   └───────────────────┘   └─────────────┘
     │                    │                    │                      │                    │
 AudioRecord          ONNX Runtime         ONNX Runtime          LiteRT-LM Runtime      Text view +
 16kHz mono PCM       (native, frame       (Silero VAD v5,       (.litertlm session)    MMS-TTS-hau
 circular buffer      -wise filter)        speech-frame gate)     or Mock stub          (ONNX Runtime)
```

Every stage logs entry/exit time to an in-memory latency ring buffer, so a slow stage is diagnosable during dev/demo without attaching a profiler.

| Component | File | Responsibility |
|---|---|---|
| `AudioCaptureManager` | `modules/zaurelink-audio/.../AudioCaptureManager.kt` | Owns the active `AudioRecord` session; wires DSP + VAD engines with graceful fallback |
| `DspFilter` (WebRTC NS) | `modules/zaurelink-audio/.../DspFilter.kt` | Noise suppression on 20ms/320-sample frames, chosen over RNNoise specifically because it natively supports 16kHz (RNNoise is 48kHz-only) |
| `VadEngine` (Silero VAD) | `modules/zaurelink-audio/.../VadEngine.kt` | Speech/silence gating on 32ms/512-sample frames via the real Silero VAD v5 ONNX model, with an energy-based VAD fallback |
| `TranslationProvider` | `modules/zaurelink-translate/.../TranslationProvider.kt` | The single interface described above |
| `AudioRoutingController` | `modules/zaurelink-audio/.../AudioRoutingController.kt` | Owns all `AudioManager` routing state; fails closed (mute + explicit prompt) on Bluetooth disconnect, never silently reroutes a private channel to a public one |
| `TtsOutputEngine` / `MmsHausaTtsEngine` | `modules/zaurelink-tts/.../` | English via Android's system TTS; Hausa via a real on-device VITS synthesis (MMS-TTS-hau) through ONNX Runtime — see [License](#license) for why |

## Tech stack

| Layer | Choice |
|---|---|
| App shell | Expo SDK 56, React Native 0.85.3, New Architecture enabled, Expo Router |
| Native modules | 3 local Expo Modules (`zaurelink-audio`, `zaurelink-translate`, `zaurelink-tts`) — custom Kotlin, not JS-only |
| Styling | NativeWind v4 (Tailwind for RN), forced always-light theme regardless of system dark mode (brand requirement) |
| Animation | React Native Reanimated 4 |
| On-device LLM | Gemma 4 E2B (int4) via LiteRT-LM `0.14.0` |
| VAD | Silero VAD v5 (ONNX, 2.3MB), energy-based fallback |
| Noise suppression | WebRTC NS (`libwebrtc-android:59-NS`, via JitPack) |
| Hausa TTS | Meta MMS-TTS (`facebook/mms-tts-hau`, VITS), ONNX export, run via ONNX Runtime Android `1.20.0` |
| English TTS | Android system `TextToSpeech` |
| Model distribution | Post-install download via `expo-file-system`, resumable across app kills, SHA-256 verified before use |

## Repository structure

```
zaurelink-mobile/
├── app/                        Expo Router screens (main UI in app/index.tsx)
├── components/                 MicOrb, VoiceWave, ModelDownloadCard, TwoOptionToggle, ...
├── lib/
│   ├── modelManager.ts          Asset download/resume/verify manager (Gemma + Hausa voice)
│   └── deviceGuards.ts          Battery/storage pre-flight checks
├── modules/
│   ├── zaurelink-audio/         Capture, DSP (WebRTC NS), VAD (Silero), routing
│   ├── zaurelink-translate/     TranslationProvider + Mock/Baseline/FineTuned implementations
│   └── zaurelink-tts/           English (system TTS) + Hausa (MMS via ONNX) synthesis
├── android/                     Native Android project (Kotlin version pin — see below)
└── eas.json                     development / preview / production build profiles
```

## Getting started

### Prerequisites

- Node.js + npm
- Android Studio with an NDK install (the build will auto-provision one if missing)
- An [EAS](https://expo.dev/eas) account for cloud builds (recommended) — this project uses **custom native modules**, so it **cannot run in Expo Go**; you need a development, preview, or production build.

### Install & build

```bash
npm install

# Development build (dev-client, connects to Metro — for active development)
eas build --profile development --platform android

# Preview build (standalone, no Metro dependency — closest to what a judge would run)
eas build --profile preview --platform android

# Production build
eas build --profile production --platform android
```

A **development build** is enough to fully exercise the app, including the ~2.6GB model download — the download itself is a native `expo-file-system` HTTP transfer straight from Hugging Face to the device's own storage, independent of the Metro/dev-client connection. Reach for a **preview build** only when you want a fully standalone, Metro-independent build — e.g. to hand the app to someone without a dev environment, or as a closer dress rehearsal for the actual demo/judging device.

> **Kotlin version note:** `litertlm-android:0.14.0` is compiled with Kotlin 2.3.0 metadata, ahead of Expo's default. `android/build.gradle` pins the Kotlin Gradle Plugin directly in the buildscript classpath (`org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0`) rather than relying on `android.kotlinVersion` in `gradle.properties`, which only propagates to dependency versions, not the compiler plugin itself.

### First-run model download

The app ships with **zero model weights** (target ~50–70MB shell on Play Store via AAB; ~255MB standalone universal APK for direct website download). On first launch:

1. The app is fully browsable in a mock/preview mode — never a blank screen while the model is missing.
2. A single-screen download flow fetches the ~2.6GB Gemma 4 E2B model and the ~109MB Hausa voice model, Wi-Fi-only by default, with a storage-space pre-check and pause/resume support.
3. The download is checksum-verified (SHA-256) before the app marks itself offline-ready.
4. The download is resumable across an app kill (not just an in-session pause) — progress state is persisted to a sidecar file and reconstructed on next launch.

**Uninstalling the app removes the downloaded model.** Both the Gemma and Hausa-voice files live under the app's private, sandboxed storage (`FileSystem.documentDirectory`) — the same location every Android/iOS app uses for its own persistent data. That directory is deliberately *not* subject to OS cache eviction while the app is installed (unlike a cache directory, which the OS can silently clear under storage pressure), but it — like the rest of an app's private storage — is wiped on uninstall by the OS itself, with no exception. A reinstall means downloading both files again from scratch.

## Known limitations & roadmap

- **Standalone Universal APK Size vs. Play Store AAB** — Without a Google Play Developer account to publish via Android App Bundle (`.aab` — which serves architecture-specific splits of ~50–70 MB per device), direct website side-loading requires a universal `.apk` (~255 MB) containing native binaries (`.so` files for LiteRT, ONNX Runtime, WebRTC) compiled for all CPU architectures (`arm64-v8a`, `x86_64`, etc.). Model weights (~2.6 GB) remain unbundled in both formats.
- **Fine-tuned model integration** — `Gemma4FineTunedProvider` is a stub pending full app integration of the Kaggle-exported LoRA-merged `.litertlm` artifact; the Baseline provider (stock Gemma 4 E2B) is used for the active demo.
- **NFR-05 (battery)** — no continuous-session battery threshold has been set yet; this needs a real power-draw measurement on the target hardware floor before it can be enforced.
- **VAD confidence threshold** is field-tunable (dev-settings slider, default 0.5) but not yet validated against real market/campus noise recordings.
- **Overlapping/simultaneous speech** is explicitly out of scope by design, not oversight — the two-microphone hardware split (earpod vs. phone mic) already solves speaker separation for the app's own two participants; true single-mic blind-source separation for two people speaking in exact unison was evaluated and rejected as infeasible within scope (tested directly against Gemma 4 E2B's native audio pathway, which collapsed a two-voice sample to one).

## Hackathon submission

| | |
|---|---|
| Event | Build With Gemma: GDG on Campus ABU Zaria, in partnership with Google DeepMind (Gemma Team) |
| Track | Gemma for Local Languages & Literacy |
| Deadline | July 25, 2026, 4:30 PM GMT+1 |

## License

This repository's own code is licensed under the [MIT License](./LICENSE) — an OSI-approved license with no restriction on commercial use.

At runtime, the app downloads two model artifacts that are **not redistributed in this repository** and retain their own upstream licenses:

| Model | License | Notes |
|---|---|---|
| Gemma 4 E2B (`litert-community/gemma-4-E2B-it-litert-lm`) | [Gemma Terms of Use](https://ai.google.dev/gemma/terms) | The base model the entire hackathon is built on |
| MMS-TTS Hausa (`facebook/mms-tts-hau`) | CC-BY-NC-4.0 | **Known, deliberately accepted conflict**: this license's non-commercial clause is not compatible with a strict reading of an OSI-approved, commercial-use-unrestricted requirement. It was evaluated and kept anyway because no other Hausa-capable, genuinely natural-sounding on-device TTS was found during this build (eSpeak NG, sometimes assumed to cover Hausa, was verified against its own source to have no Hausa voice at all). This is disclosed here deliberately rather than silently — anyone building on this repo for commercial use should replace this component first. |

## Acknowledgments

Built for **Build With Gemma: GDG on Campus ABU Zaria** by the ZaureLink team — a mobile/Android engineer and an AI/fine-tuning engineer.
