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

## Table of Contents

- [The Problem](#the-problem)
- [What ZaureLink Does](#what-zaurelink-does)
- [Repository Structure](#repository-structure)
- [System Architecture](#system-architecture)
- [How Gemma 4 Powers ZaureLink](#how-gemma-4-powers-zaurelink)
- [AI Engineering Pipeline](#ai-engineering-pipeline)
- [On-Device Audio Pipeline](#on-device-audio-pipeline)
- [Quick Start](#quick-start)
- [Hackathon Submission](#hackathon-submission)
- [License](#license)

---

## The Problem

Students and residents around Ahmadu Bello University (Samaru, Zaria) cross the Hausa ↔ English boundary constantly — in markets, on Keke Napep rides, at campus clinics, in lecture halls. Connectivity in these environments is unreliable or absent, and cloud translation apps fail the moment the signal drops.

But the problem is deeper than connectivity. Generic machine translation fails on the domain-specific language people actually use:

| Context | What people say | What generic MT gets wrong |
|---|---|---|
| Market | *"dari biyar"* | Doesn't know this means ₦500 (not "five hundred") |
| Market | *"farashi na karshe"* | Misses the negotiation idiom ("final price") |
| Transport | *"Keke"*, *"Okada"* | Can't map local vehicle names |
| Clinic | *"Ina jin zazzabi"* | Generic "I feel fever" vs. clinical "I have a fever" |
| Campus | *"Zan je in mika masa assignment dina"* | Loses academic register |

ZaureLink solves both problems: an **on-device LLM** with **domain-aware prompt engineering** that works without any network connection.

---

## What ZaureLink Does

1. **Two domain-tuned modes** — *Market Mode* (bargaining, currency shorthand, measurement units) and *Campus Mode* (transport, clinical phrasing, academic terms).
2. **Genuinely bidirectional** — either party speaks Hausa or English; user selects their language per session.
3. **Pass-through / never over-translates** — relays utterances unchanged if the speaker already uses the listener's target language, including mid-sentence code-switching.
4. **Privacy-first hardware routing** — earpiece is the user's private I/O; phone mic/speaker is public. Bluetooth disconnect **fails closed** (halts and mutes).
5. **Conversation memory** — bounded 8-turn sliding window resolves references (e.g., *"reduce it"* to a quoted price 3 turns earlier).
6. **Sunlight-legible UI** — high-contrast, large-type transcript designed for outdoor use in direct Nigerian midday sun.
7. **Zero weights in APK** — thin shell install (~50 MB); ~2.4 GB Gemma 4 E2B model + ~109 MB Hausa TTS download post-install with resume and SHA-256 verification.

---

## Repository Structure

| Directory | Description | README |
|---|---|---|
| [`zaurelink-mobile/`](./zaurelink-mobile) | **The Android app.** Expo / React Native with three custom native Kotlin modules. This is the submission deliverable. | [**Full technical README →**](./zaurelink-mobile/README.md) |
| [`zaurelink-ai/`](./zaurelink-ai) | **AI engineering.** Data pipeline, fine-tuning notebooks, model export, dataset, and replication documentation. | [**AI engineering README →**](./zaurelink-ai/README.md) |
| [`zaurelink/`](./zaurelink) | **Landing page.** Marketing / demo site (Next.js 16, Tailwind v4) hosting the direct APK download. | [README →](./zaurelink/README.md) |

> **How to read this repo:** This root README provides the system-level overview. Each component directory above has its own deep-dive README covering architecture, build instructions, and implementation details specific to that component.

---

## System Architecture

The end-to-end pipeline from fieldwork data to on-device translation:

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           AI ENGINEERING PIPELINE                              │
│                                                                                 │
│  Fieldwork Seeds ──► Synthetic Expansion ──► Deterministic Recombination        │
│  (15-25 pairs/env)    (Gemini AI Studio)     (1,048 training records)          │
│                                                                                 │
│  Training Data ──► QLoRA Fine-Tuning ──► LoRA Merge ──► LiteRT-LM Export      │
│  (.jsonl)           (Colab T4 GPU)       (16-bit)      (Kaggle TPU v5e-8)     │
│                                                                                 │
│  Final Artifact: zaurelink-translator-v1.litertlm (~2.4 GB, INT4)             │
│  Hosted: huggingface.co/israel-ayeni/ZaureLink                                │
└─────────────────────────────────┬───────────────────────────────────────────────┘
                                  │ Downloaded post-install
                                  ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          ON-DEVICE INFERENCE PIPELINE                           │
│                                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌────────────────┐   ┌──────────────┐  │
│  │ Audio Capture │──►│  DSP Filter  │──►│ VAD Gatekeeper │──►│ Translation  │  │
│  │ (mic route)   │   │ (WebRTC NS)  │   │ (Silero VAD)   │   │ Provider     │  │
│  └──────────────┘   └──────────────┘   └────────────────┘   └──────┬───────┘  │
│       │                    │                    │                    │          │
│   AudioRecord          ONNX Runtime         ONNX Runtime        LiteRT-LM    │
│   16kHz mono PCM       (noise suppression)  (speech gating)     (Gemma 4)    │
│                                                                    │          │
│                                                              ┌─────▼───────┐  │
│                                                              │ UI + TTS    │  │
│                                                              │ Output      │  │
│                                                              └─────────────┘  │
│                                                              Text view +      │
│                                                              MMS-TTS-hau      │
│                                                              (ONNX Runtime)   │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### Hard System Constraints

| ID | Constraint | Target |
|---|---|---|
| NFR-01 | End-to-end latency | ≤ 1.5 s (utterance end → audio output) |
| NFR-02 | Active RAM | ≤ 1.5 GB |
| NFR-03 | Initial APK size | ≤ 50 MB |
| NFR-04 | Network dependency | Zero post-download |
| NFR-05 | Hardware floor | ≤ 4 GB RAM, Snapdragon 4-series |

---

## How Gemma 4 Powers ZaureLink

### Where Gemma 4 Lives in This Codebase

| File | Role |
|---|---|
| [`Gemma4BaselineProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/Gemma4BaselineProvider.kt) | **The core Gemma 4 integration.** Loads `gemma-4-E2B-it` (int4) through LiteRT-LM's Kotlin API — `Engine` / `EngineConfig` / `Conversation` — and holds the four domain system prompts, speaker-tag construction, and sampler/backend tuning. |
| [`TranslationProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/TranslationProvider.kt) | The swap boundary every tier implements, plus the `Environment` / `AppUserLanguage` / `ActiveChannel` model. |
| [`ZaurelinkTranslateModule.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/ZaurelinkTranslateModule.kt) | The JS↔native bridge. Session handles and tier switching; only opaque ids and text cross the bridge. |
| [`Gemma4FineTunedProvider.kt`](./zaurelink-mobile/modules/zaurelink-translate/android/src/main/java/expo/modules/zaurelinktranslate/Gemma4FineTunedProvider.kt) | The fine-tuned tier slot (loads the QLoRA-merged, exported model). |
| [`modelManager.ts`](./zaurelink-mobile/lib/modelManager.ts) | Post-install model delivery: resumable download from [Hugging Face](https://huggingface.co/israel-ayeni/ZaureLink/resolve/main/zaurelink-translator-v1.litertlm?download=true), SHA-256 verification, storage/battery guards. |

### Three-Tier Provider Architecture

All app code interacts with a single `TranslationProvider` interface:

1. **`MockTranslationProvider`** — Rule-based responses without model execution (enables parallel UI/audio development).
2. **`Gemma4BaselineProvider`** — Runs stock Gemma 4 E2B on LiteRT-LM to validate latency, RAM, and conversation windowing.
3. **`Gemma4FineTunedProvider`** — Runs the QLoRA-merged, INT4-quantized model fine-tuned on ZaureLink's custom dataset.

### Domain System Prompts & Speaker Tagging

Four fixed system prompts are selected at session start based on `Environment (Market / Campus) × AppUserLanguage (en / ha)`. Each prompt enforces three strict rules:

1. **Pass-through** — If input language matches the listener's language, relay unchanged.
2. **Translate** — If input differs from the listener's language, translate.
3. **Mixed utterance** — Code-switched sentences are translated as a single conceptual unit.

Speaker identity is mechanically tagged per turn:
- Earpod (App User): `app_user:`
- Phone Mic (Market): `trader:` / Phone Mic (Campus): `other_party:`

### On-Device LLM Configuration

```json
{
  "model": "litert-community/gemma-4-E2B-it-litert-lm",
  "backend": "cpu_xnnpack",
  "num_threads": 4,
  "mmap_model_weights": true,
  "context_length": 2048,
  "cache_length": 1536
}
```

Weights are memory-mapped (`mmap`) directly from disk — not loaded into JVM heap. Resident RAM is dominated by KV cache and activation buffers, not the full ~2.4 GB model file.

---

## AI Engineering Pipeline

The complete AI/ML pipeline is documented in [`zaurelink-ai/`](./zaurelink-ai/README.md). Here is the summary:

### Data Engineering

A hybrid approach: real fieldwork seeds from ABU Zaria markets and campus, multiplied via synthetic expansion (Gemini AI Studio), then deterministically recombined to cover all 4 prompt configurations.

```
524 fieldwork+synthetic EN records ──► deterministic recombination ──► 1,048 training records
                                       (hausa_equivalents.json)        (balanced 4-way split)
```

| Environment | EN Config | HA Config | Total |
|---|---|---|---|
| Market | 276 | 276 | 552 |
| Campus | 248 | 248 | 496 |
| **Total** | **524** | **524** | **1,048** |

### Fine-Tuning

| Detail | Value |
|---|---|
| Base Model | `google/gemma-4-E2B-it` (5.15B params) |
| Method | QLoRA (Unsloth) — rank 16, α=16, 0.49% trainable |
| Environment | Google Colab, NVIDIA T4 (16 GB VRAM) |
| Training | 1 epoch, 118 steps, 14 minutes |
| Loss | 1.07 → 0.02 |
| Notebook | [`zaurelink-ai/notebooks/01_fine_tuning.ipynb`](./zaurelink-ai/notebooks/01_fine_tuning.ipynb) |

### Model Export

| Detail | Value |
|---|---|
| Tool | `litert-torch export_hf` |
| Quantization | `dynamic_wi4_afp32` (INT4 weights, FP32 activations) |
| Environment | Kaggle TPU v5e-8 (~90 GB RAM — needed for export memory) |
| Compression | 8.5 GiB → 1.09 GiB (7.8×) |
| Export Time | 7 minutes 8 seconds |
| Artifact | [`zaurelink-translator-v1.litertlm`](https://huggingface.co/israel-ayeni/ZaureLink/resolve/main/zaurelink-translator-v1.litertlm?download=true) |
| SHA-256 | `48ee9a559e748dc08b9938733b53fd09d75f02d4c65114ba4b9f24795d1013bd` |
| Notebook | [`zaurelink-ai/notebooks/03_litertlm_export.ipynb`](./zaurelink-ai/notebooks/03_litertlm_export.ipynb) |

### Diarization Experiment (Rejected)

Gemma 4 E2B's multi-speaker diarization was [empirically tested and rejected](./zaurelink-ai/notebooks/02_diarization_experiment.ipynb) — the model collapsed overlapping speakers and suffered token looping. ZaureLink uses hardware-based speaker separation (dual-mic routing) instead.

---

## On-Device Audio Pipeline

| Component | File | Role |
|---|---|---|
| Audio Capture | [`AudioCaptureManager.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/AudioCaptureManager.kt) | 16 kHz mono PCM circular buffer with dual-channel routing |
| Noise Suppression | [`DspFilter.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/DspFilter.kt) | WebRTC NS on 20 ms / 320-sample frames |
| Voice Activity Detection | [`VadEngine.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/VadEngine.kt) | Silero VAD v5 (ONNX) on 32 ms / 512-sample frames |
| Audio Routing | [`AudioRoutingController.kt`](./zaurelink-mobile/modules/zaurelink-audio/android/src/main/java/expo/modules/zaurelinkaudio/AudioRoutingController.kt) | Privacy routing — fails closed on BT disconnect |
| Hausa TTS | [`MmsHausaTtsEngine.kt`](./zaurelink-mobile/modules/zaurelink-tts/android/src/main/java/expo/modules/zaurelinktts/MmsHausaTtsEngine.kt) | Meta MMS-TTS (VITS) via ONNX Runtime — Android has no native Hausa voice |
| English TTS | Android `TextToSpeech` | System engine |
| UI | [`app/index.tsx`](./zaurelink-mobile/app/index.tsx) | Mic orb, per-turn routing indicator, transcript, settings |

---

## Quick Start

### Mobile App (the submission)

```bash
cd zaurelink-mobile
npm install
npx expo prebuild --platform android   # only if regenerating native projects
eas build --platform android --profile preview
```

The app is usable immediately in its Mock tier. The offline Gemma tier activates after the in-app one-time model download (~2.4 GB) completes and passes checksum verification. Full instructions in the [mobile README](./zaurelink-mobile/README.md#getting-started).

### AI Pipeline (reproduction)

1. **Dataset processing:** Run the scripts in [`zaurelink-ai/dataset/`](./zaurelink-ai/dataset/) in order (see [data pipeline docs](./zaurelink-ai/README.md#data-pipeline))
2. **Fine-tuning:** Open [`01_fine_tuning.ipynb`](./zaurelink-ai/notebooks/01_fine_tuning.ipynb) in Google Colab with a T4 GPU
3. **Model export:** Open [`03_litertlm_export.ipynb`](./zaurelink-ai/notebooks/03_litertlm_export.ipynb) in Kaggle with a TPU v5e-8 instance

Full step-by-step reproduction guide: [`zaurelink-ai/docs/replication_guide.md`](./zaurelink-ai/docs/replication_guide.md)

### Landing Page

```bash
cd zaurelink
npm install
npm run dev
```

---

## Hackathon Submission

| Field | Value |
|---|---|
| **Event** | Build With Gemma: GDG on Campus ABU Zaria |
| **Partner** | Google DeepMind (Gemma Team) |
| **Track** | Track 1 — Gemma for Local Languages & Literacy |
| **Deadline** | July 25, 2026, 4:30 PM GMT+1 |
| **Fine-tuned Model** | [`israel-ayeni/ZaureLink`](https://huggingface.co/israel-ayeni/ZaureLink) on Hugging Face |

---

## License

This repository's own code is [MIT licensed](./zaurelink-mobile/LICENSE) — OSI-approved, no restriction on commercial use.

Model artifacts are downloaded at runtime and **not redistributed here**; they retain their upstream licenses:

| Model | License | Notes |
|---|---|---|
| Gemma 4 E2B | [Google Gemma Terms of Use](https://ai.google.dev/gemma/terms) | Downloaded via `modelManager.ts` |
| ZaureLink fine-tune | [Apache 2.0](https://huggingface.co/israel-ayeni/ZaureLink) | QLoRA-merged, INT4 quantized |
| MMS-TTS Hausa | CC-BY-NC-4.0 | Non-commercial clause — [see mobile README](./zaurelink-mobile/README.md#license) for full disclosure |
| Silero VAD v5 | MIT | ONNX model, 2.3 MB |

The MMS-TTS Hausa non-commercial clause is disclosed deliberately — it was the only high-quality on-device Hausa TTS available (eSpeak NG lacks Hausa support entirely). See the [license section of the mobile README](./zaurelink-mobile/README.md#license) for the full reasoning and what to replace for commercial use.
