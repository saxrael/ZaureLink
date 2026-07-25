<p align="center">
  <img src="./zaurelink-mobile/assets/images/zaurelink-logo.png" alt="ZaureLink" width="220" />
</p>

<h1 align="center">ZaureLink</h1>
<p align="center"><strong>Offline, bidirectional Hausa ↔ English speech-to-speech translation — 100% on-device, powered by Gemma 4.</strong></p>

<p align="center">
  <img alt="Track" src="https://img.shields.io/badge/Track-Gemma%20for%20Local%20Languages%20%26%20Literacy-fa9923">
  <img alt="Hackathon" src="https://img.shields.io/badge/Build%20With%20Gemma-GDG%20on%20Campus%20ABU%20Zaria-1f355f">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-blue">
  <img alt="Platform" src="https://img.shields.io/badge/platform-Android-3ddc84">
</p>

---

ZaureLink runs a complete speech-to-speech translator between Hausa and English entirely on a phone's own CPU — no server, no API call, no data plan at any point after the one-time model download. It exists because the people who most need it (market traders, Keke Napep passengers, patients describing symptoms to a chemist) are almost always the people with the least reliable connectivity, and cloud translation apps stop working the moment the signal does.

Built for **Build With Gemma: GDG on Campus ABU Zaria**, in partnership with Google DeepMind (Gemma Team). Submitted under **Track 1 — Gemma for Local Languages & Literacy**.

## What's in this repository

| Directory | What it is |
|---|---|
| [`zaurelink-mobile/`](./zaurelink-mobile) | **The app.** Expo / React Native (New Architecture) with three custom native Kotlin modules. This is the submission. → **[Full technical README](./zaurelink-mobile/README.md)** |
| [`zaurelink/`](./zaurelink) | The marketing / demo landing page (Next.js 16, Tailwind v4), which also hosts the direct APK download. |

**Start here for the technical detail:** [`zaurelink-mobile/README.md`](./zaurelink-mobile/README.md) covers the architecture, the three-tier provider strategy, the four domain system prompts, the speaker-tag mechanism, build instructions, and known limitations in full.

## Where Gemma 4 actually lives in this codebase

Rather than make you hunt for it, these are the exact files that constitute the Gemma 4 integration:

| File | Role |
|---|---|
| [`Gemma4BaselineProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/Gemma4BaselineProvider.kt) | **The core Gemma 4 integration.** Loads `gemma-4-E2B-it` (int4) through LiteRT-LM's real Kotlin API (`com.google.ai.edge.litertlm`) — `Engine` / `EngineConfig` / `Conversation` — and holds the four verbatim domain system prompts, the speaker-tag construction, sampler/backend tuning, and the audio+text content path. |
| [`TranslationProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/TranslationProvider.kt) | The swap boundary every tier implements, plus the `Environment` / `AppUserLanguage` / `ActiveChannel` model and the `requiredOutputLanguage()` derivation. |
| [`ZaurelinkTranslateModule.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/ZaurelinkTranslateModule.kt) | The JS↔native bridge. Session handles and tier switching; only opaque ids and text cross the bridge — never raw PCM. |
| [`MockTranslationProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/MockTranslationProvider.kt) · [`Gemma4FineTunedProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/Gemma4FineTunedProvider.kt) | The other two tiers: a zero-dependency demo tier, and the fine-tuned slot (a stub — see limitations). |
| [`modelManager.ts`](./zaurelink-mobile/lib/modelManager.ts) | Post-install model delivery: resumable download, SHA-256 verification, storage/battery guards. The ~2.6GB model is deliberately **not** bundled in the APK. |

Supporting on-device pipeline (all real inference, no cloud):

| File | Role |
|---|---|
| [`AudioCaptureManager.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/AudioCaptureManager.kt) · [`VadEngine.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/VadEngine.kt) · [`DspFilter.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/DspFilter.kt) | 16kHz capture, Silero VAD v5 (ONNX) utterance segmentation, WebRTC noise suppression, and the dual-channel (earpod / phone mic) privacy routing. |
| [`MmsHausaTtsEngine.kt`](./zaurelink-mobile/modules/zaurelink-tts/android/src/main/java/expo/modules/zaurelinktts/MmsHausaTtsEngine.kt) | On-device Hausa speech synthesis via Meta MMS-TTS (VITS) on ONNX Runtime — because Android has no Hausa TTS voice, and eSpeak NG genuinely has none either (verified against its own source). |
| [`app/index.tsx`](./zaurelink-mobile/app/index.tsx) | The single-screen UI: mic orb, per-turn routing indicator, transcript, and the settings surface. |

## Quick start

```bash
cd zaurelink-mobile
npm install
npx expo prebuild --platform android   # native projects are committed; only if regenerating
eas build --platform android --profile preview
```

The app is usable immediately in its Mock tier; the offline Gemma tier activates after the in-app one-time model download completes and passes checksum verification. Full instructions, including the model-download flow, are in the [mobile README](./zaurelink-mobile/README.md#getting-started).

## License

This repository's own code is [MIT licensed](./zaurelink-mobile/LICENSE) — OSI-approved, no restriction on commercial use.

Model artifacts are downloaded at runtime and **not redistributed here**; they retain their upstream licenses. One of them (MMS-TTS Hausa, CC-BY-NC-4.0) carries a known non-commercial clause that we disclose deliberately rather than silently — see the [license section of the mobile README](./zaurelink-mobile/README.md#license) for the full reasoning and what to replace for commercial use.
