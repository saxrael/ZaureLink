# ZaureLink v1.0.0 — Official Release

**Offline, bidirectional Hausa ↔ English speech-to-speech translation — 100% on-device, powered by fine-tuned Gemma 4 E2B.**

Built for **Build With Gemma: GDG on Campus ABU Zaria**, in partnership with Google DeepMind (Gemma Team).  
Submitted under **Track 1 — Gemma for Local Languages & Literacy**.

---

## 🌟 Release Highlights

- **100% On-Device & Zero Cloud Dependency**: Operates completely offline on low-end Android hardware after a one-time setup. No API calls or server processing at any point during translation.
- **Fine-Tuned Gemma 4 E2B Engine**: Powered by `google/gemma-4-E2B-it`, fine-tuned via QLoRA on a custom 1,048-record Hausa ↔ English conversational dataset from ABU Zaria markets and campus.
- **Two Domain Modes**:
  - **Market Mode**: Handles bargaining shorthand (*dari biyar* → ₦500), negotiation idioms (*farashi na karshe*), and local measurement units (*mudu*, *tiya*).
  - **Campus Mode**: Tuned for transport (*Keke Napep*, *Okada*), clinical symptom descriptions (*Ina jin zazzabi* → *I have a fever*), and academic/administrative interactions.
- **Code-Switching & Pass-Through**: Automatically detects spoken language; if the speaker uses a language the listener already understands, output is relayed unchanged.
- **Privacy-First Dual-Mic Routing**: Earpiece is private to the user; phone mic/speaker is public. Bluetooth disconnect **fails closed** (halts and mutes).
- **On-Device Audio Stack**: WebRTC Noise Suppression + Silero VAD v5 + Meta MMS-TTS (VITS) for Hausa speech synthesis.

---

## 📱 Installation Instructions

### Step 1: Install the Android Application
1. Download `zaurelink.apk` (243.7 MB) from the Assets section below.
2. Open the downloaded APK on your Android device (Android 7.0+ / API 24+ required).
3. If prompted, allow **Install from Unknown Sources** in your browser/file manager settings.
4. Launch **ZaureLink**.

### Step 2: One-Time Offline Model Download
1. On first launch, the app runs in **Mock Mode** (browsable demo).
2. Open the model downloader in settings to fetch the **Gemma 4 E2B model (~2.4 GB)** and **Hausa TTS voice model (~109 MB)**.
3. Once checksum verification (SHA-256) passes, the offline **Gemma 4 Fine-Tuned** tier is active and ready for airplane-mode operation.

---

## 🔍 Technical Disclosures & Specifications

### Hardware Floor & Performance Targets
- **Target Hardware**: ≤4GB RAM, Snapdragon 4-series CPU
- **End-to-End Latency Target**: ≤1.5s (utterance end → audio output)
- **Active RAM Footprint**: ≤1.5GB (achieved via LiteRT `mmap` weight paging & 1536-token bounded KV cache)

### Standalone Universal APK Packaging Rationale
- **Format**: Universal `.apk` (243.71 MB)
- **Packaging Note**: On the Google Play Store using an Android App Bundle (`.aab`), dynamic delivery splits native libraries per CPU architecture, resulting in a **~50–70 MB** initial download. Because this release is distributed via direct side-loading on the website / GitHub Releases, a universal standalone `.apk` bundling native shared libraries (`.so` files for LiteRT, ONNX Runtime, WebRTC, XNNPACK) for all CPU architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`) is required. Model weights (~2.4 GB) are completely unbundled from the APK binary in both formats.

### Third-Party License Disclosure
- **App Code**: MIT License
- **Fine-Tuned Model**: Apache 2.0 (hosted on [Hugging Face `israel-ayeni/ZaureLink`](https://huggingface.co/israel-ayeni/ZaureLink))
- **MMS-TTS Hausa**: CC-BY-NC-4.0 (disclosed non-commercial license clause; selected as the only high-quality on-device Hausa TTS engine available)

---

## 🔐 Checksums & Verification

| Asset | File Name | SHA-256 Checksum | Size |
|---|---|---|---|
| Android App | `zaurelink.apk` | `dcf505ffb4168e22151957408f4db09fed53a928e349f899eb1373a2c040522e` | 243.71 MiB (255,545,602 B) |
| Fine-Tuned Model | `zaurelink-translator-v1.litertlm` | `48ee9a559e748dc08b9938733b53fd09d75f02d4c65114ba4b9f24795d1013bd` | ~2.4 GB |

---

## 🔗 Quick Links
- **Project Repository**: [github.com/saxrael/ZaureLink](https://github.com/saxrael/ZaureLink)
- **AI Engineering Docs**: [`zaurelink-ai/README.md`](https://github.com/saxrael/ZaureLink/tree/main/zaurelink-ai)
- **Mobile Technical Docs**: [`zaurelink-mobile/README.md`](https://github.com/saxrael/ZaureLink/tree/main/zaurelink-mobile)
- **Model Card (Hugging Face)**: [huggingface.co/israel-ayeni/ZaureLink](https://huggingface.co/israel-ayeni/ZaureLink)
