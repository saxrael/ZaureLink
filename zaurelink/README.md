# ZaureLink — Marketing & Download Landing Page

This directory contains the marketing and direct-download landing page for **ZaureLink**, built with **Next.js 16**, **Tailwind CSS v4**, and **TypeScript**.

It serves as the public web interface for the project and hosts the direct APK download link via **GitHub Releases**.

---

## 🌟 Key Features

1. **Direct APK Download**: Links directly to the published standalone Android release asset hosted on GitHub Releases (`https://github.com/saxrael/ZaureLink/releases/download/v1.0.0/zaurelink.apk`).
2. **Asset Detection (`publicAsset`)**: Integrates server-side asset verification (`app/lib/publicAsset.ts`) to dynamically detect file availability and display exact binary size labels.
3. **Interactive Audio & Feature Demo**: Previews Market Mode vs. Campus Mode conversation flows.
4. **Sunlight-Legible Design**: Styled with curated dark-mode glassmorphism aesthetics.

---

## 🚀 Getting Started

### Local Development

```bash
cd zaurelink
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) with your browser.

### Build & Production Check

```bash
npm run build
npm run start
```

---

## ⚙️ Configuration & Download URL Wiring

The download CTA is driven by [`app/components/Download.tsx`](./app/components/Download.tsx):

```typescript
// Configured to point directly to the published GitHub Release asset
const EXTERNAL_APK_URL = "https://github.com/saxrael/ZaureLink/releases/download/v1.0.0/zaurelink.apk";
```

### Downloading Assets outside Git (Git Size Limits)
Because standard Git repositories enforce a 100MB per-file limit (and the standalone universal APK is ~243.7MB), the binary APK is hosted via **GitHub Releases** rather than tracked inside `public/downloads/`. The website download button directly links to the external release URL, eliminating build output limits and repository bloat.

---

## 📄 License & Subsystem Links

- **Main Repository**: [github.com/saxrael/ZaureLink](../README.md)
- **Mobile App**: [`zaurelink-mobile/`](../zaurelink-mobile/README.md)
- **AI Engineering**: [`zaurelink-ai/`](../zaurelink-ai/README.md)
