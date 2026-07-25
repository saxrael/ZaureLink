<p align="center">
  <img src="../zaurelink-mobile/assets/images/zaurelink-logo.png" alt="ZaureLink" width="180" />
</p>

<h1 align="center">ZaureLink — AI Engineering</h1>
<p align="center"><strong>Fine-tuned Gemma 4 E2B pipeline for offline Hausa↔English translation on Android</strong></p>

---

This directory contains everything needed to reproduce the AI/ML side of ZaureLink: the data pipeline, fine-tuning notebook, diarization experiment, model export, and supporting documentation.

> **Looking for the mobile app?** See [`zaurelink-mobile/`](../zaurelink-mobile/README.md).  
> **Looking for the landing page?** See [`zaurelink/`](../zaurelink/README.md).

## Table of Contents

- [System Constraints](#system-constraints)
- [Architecture Decisions](#architecture-decisions)
- [Data Pipeline](#data-pipeline)
- [Fine-Tuning](#fine-tuning)
- [Model Export](#model-export)
- [Notebooks](#notebooks)
- [Dataset Structure](#dataset-structure)
- [Repository Layout](#repository-layout)
- [Compliance & Licensing](#compliance--licensing)

---

## System Constraints

Every AI decision in this project is governed by five hard, non-negotiable ceilings — set by the target deployment environment (low-end Android phones in Northern Nigeria):

| ID | Constraint | Target | How It's Met |
|---|---|---|---|
| NFR-01 | End-to-end latency | ≤ 1.5 s (utterance end → audio output) | Token caps, bounded KV cache, per-stage timing ring buffer |
| NFR-02 | Active RAM | ≤ 1.5 GB | `mmap` weight loading, `cache_length=1536`, 8-turn sliding window |
| NFR-03 | Initial APK size | ≤ 50 MB | Model downloaded separately post-install (~2.4 GB) |
| NFR-04 | Network dependency | Zero post-download | LiteRT-LM inference, no cloud calls |
| NFR-05 | Hardware floor | ≤ 4 GB RAM, Snapdragon 4-series | CPU-only XNNPACK, 4 threads |

> **Disk vs. RAM:** The on-disk model is ~2.4 GB, well above the 1.5 GB RAM ceiling. This works because LiteRT-LM uses `mmap` to demand-page weights from disk — resident RAM is dominated by KV cache and activation buffers, not the full weight tensor.

---

## Architecture Decisions

### Language Model: Gemma 4 E2B

[`google/gemma-4-E2B-it`](https://huggingface.co/google/gemma-4-E2B-it) (5.15B parameters) was selected as the smallest Gemma 4 variant with native multilingual + audio understanding. Fine-tuned with QLoRA (rank 16, ~25.3M trainable parameters / 0.49% of total).

### Session-Level Language Architecture

Early prototypes used a `Direction` enum that conflated three distinct concepts (mic channel, user's language, spoken language). This was replaced with a cleaner model:

| Concept | Scope | Values |
|---|---|---|
| `ActiveChannel` | Per-turn, mechanical | Earpod mic / Phone mic |
| `AppUserLanguage` | Per-session, chosen once | `en` / `ha` |
| Spoken language | Per-turn, auto-detected by model | — |

**Derived action logic:**
- Earpod mic active → listener is counterpart → output requires opposite of `AppUserLanguage`
- Phone mic active → listener is app user → output requires `AppUserLanguage`
- If spoken language = required output → **pass-through** (relay unchanged)
- If spoken language ≠ required output → **translate**

This produces **4 stable system prompts**: `Environment (Market / Campus) × AppUserLanguage (en / ha)`.

### Multi-Speaker Handling: Diarization Rejection

Gemma 4 E2B's diarization capability was empirically tested ([notebook 02](./notebooks/02_diarization_experiment.ipynb)) and **rejected**:
- The model collapsed 2 overlapping speakers into 1 voice
- Suffered degenerate token looping (180+ repeated tokens)
- Semantic-focus prompting made latency *worse*

**Solution:** Hardware-based speaker separation via dual-mic routing (earpod = private, phone mic = public) with front-end DSP (WebRTC noise suppression, Silero VAD gating).

---

## Data Pipeline

### Overview

A hybrid approach: scarce real-world fieldwork seeds, multiplied via synthetic cloud-LLM augmentation (Gemini via AI Studio), then deterministically recombined to cover all 4 system prompt configurations.

```
raw_batches/batch_001.json … batch_011.json (524 EN-config records)
  │
  ├── merge_batches.py
  ▼
zaurelink_training_data.json (524 records)
  │
  ├── hausa_equivalents.json (514 manual lookup entries)
  ├── canonical_prompts.py (4 system prompts)
  ├── recombine_app_user_language.py
  ▼
zaurelink_training_data_recombined.json (1,048 records: 524 EN + 524 HA)
  │
  ├── compile_to_jsonl.py
  ▼
zaurelink_training_data.jsonl (1,048 JSONL lines — loaded by fine-tuning notebook)
```

### Stage 1: Fieldwork Collection

Real conversational data was collected from markets and campus environments around ABU Zaria. The original fieldwork data collection sheet is preserved as [`fieldwork_seeds.xlsx`](./dataset/fieldwork_seeds.xlsx).

- 15–25 real seed phrase pairs per environment (Market / Campus)
- 4–6 multi-turn scenarios per environment (max 8 exchanges to match runtime sliding window)
- Metadata: `original_spoken_language` (`ha`/`en`) and `hausa_equivalent_natural`

### Stage 2: Synthetic Expansion & Verification

Each fieldwork seed expanded via Gemini (AI Studio) into 10–15 paraphrased variants in both translation directions. Mandatory back-translation or manual bilingual review (`verified: true`) to prevent generic Hausa from diluting authentic regional speech patterns.

### Stage 3: Deterministic Recombination

A custom Python script (`recombine_app_user_language.py`) — **not an LLM** — generates the mirrored `app_user_language: "ha"` records from the EN records. Uses `hausa_equivalents.json` for human-verified mappings. This eliminates LLM semantic drift in the mirroring process.

### Stage 4: Balance Correction

Explicit balance check across 4 cells:

| Environment | App User Language | Records | Percentage |
|---|---|---|---|
| Market | English | 276 | 26.3% |
| Market | Hausa | 276 | 26.3% |
| Campus | English | 248 | 23.7% |
| Campus | Hausa | 248 | 23.7% |
| **Total** | | **1,048** | **100%** |

---

## Fine-Tuning

### Environment

Google Colab with NVIDIA Tesla T4 (16 GB VRAM). Full pipeline in [notebook 01](./notebooks/01_fine_tuning.ipynb).

### QLoRA Configuration

| Parameter | Value |
|---|---|
| Base Model | `google/gemma-4-E2B-it` |
| Quantization | 4-bit NormalFloat (NF4) |
| Max Sequence Length | 2048 tokens |
| LoRA Rank (*r*) | 16 |
| LoRA Alpha (*α*) | 16 |
| LoRA Dropout | 0.05 |
| Target Modules | All linear layers (`q_proj`, `k_proj`, `v_proj`, `o_proj`, `gate_proj`, `up_proj`, `down_proj`) |
| Trainable Parameters | ~25.3M / 5.15B (0.49%) |

### Training Configuration

| Parameter | Value |
|---|---|
| Epochs | 1 |
| Per-device Batch Size | 2 |
| Gradient Accumulation | 4 steps |
| **Effective Batch Size** | **8** |
| Total Steps | 118 |
| Learning Rate | 2e-4 (linear decay) |
| Warmup Steps | 10 |
| Weight Decay | 0.05 |
| Optimizer | 8-bit AdamW |

### Results

- **Training time:** 14 minutes 13 seconds
- **Loss curve:** 1.07 → 0.02 (converged by step 50)
- **Validation samples:** Correctly translated held-out Hausa↔English pairs
- **OOD testing:** 8 unseen scenarios (market haggling, clinical symptoms, multi-turn negotiations) produced accurate, contextually appropriate translations

---

## Model Export

### The Memory Wall

`litert-torch export_hf` requires holding merged FP32 weights and quantization buffers concurrently. This exceeded:
- Colab free tier (~13 GB system RAM) — 4 failed attempts documented in [notebook 01](./notebooks/01_fine_tuning.ipynb)
- Standard Kaggle GPU (~30 GB system RAM)

**Resolution:** Kaggle TPU v5e-8, selected strictly for its ~90 GB system RAM. See [notebook 03](./notebooks/03_litertlm_export.ipynb).

### Export Recipe

```bash
litert-torch export_hf \
  --model_path=/kaggle/input/datasets/saxrael/zaurelink/zaurelink_merged_safe \
  --quantize=dynamic_wi4_afp32 \
  --cache_length=1536 \
  --prefill_lengths=128,256 \
  --externalize_embedder \
  --bundle_litert_lm \
  --use_jinja_template
```

### Quantization Results

| Component | Original | Quantized | Compression |
|---|---|---|---|
| Main model (`model.tflite`) | 8.50 GiB | 1.09 GiB | 7.8× |
| Embedder (`embedder.tflite`) | 1.50 GiB | 198 MiB | 7.8× |
| Per-layer embedder | 8.75 GiB | 1.10 GiB | 8.0× |

- **Export time:** 7 minutes 8 seconds
- **Final artifact:** `zaurelink-translator-v1.litertlm`
- **SHA-256:** `48ee9a559e748dc08b9938733b53fd09d75f02d4c65114ba4b9f24795d1013bd`

### Model Hosting

The exported model is hosted on Hugging Face and downloaded at runtime by the mobile app:

- **Repository:** [`israel-ayeni/ZaureLink`](https://huggingface.co/israel-ayeni/ZaureLink)
- **Direct download:** [`zaurelink-translator-v1.litertlm`](https://huggingface.co/israel-ayeni/ZaureLink/resolve/main/zaurelink-translator-v1.litertlm?download=true)
- **Model card:** [`docs/MODEL_CARD.md`](./docs/MODEL_CARD.md)

The mobile app's [`modelManager.ts`](../zaurelink-mobile/lib/modelManager.ts) handles resumable download with SHA-256 verification.

---

## Notebooks

| # | Notebook | Environment | Purpose |
|---|---|---|---|
| 01 | [`01_fine_tuning.ipynb`](./notebooks/01_fine_tuning.ipynb) | Google Colab (T4 GPU) | QLoRA fine-tuning, evaluation, and weight merging |
| 02 | [`02_diarization_experiment.ipynb`](./notebooks/02_diarization_experiment.ipynb) | Google Colab (T4 GPU) | Empirical test of Gemma 4 E2B diarization capability |
| 03 | [`03_litertlm_export.ipynb`](./notebooks/03_litertlm_export.ipynb) | Kaggle (TPU v5e-8) | LiteRT-LM model export with INT4 quantization |

---

## Dataset Structure

```
dataset/
├── raw_batches/                          # 11 JSON files, 524 EN-config records
│   ├── batch_001.json … batch_011.json
├── scripts/                              # Maintenance & audit utilities
│   ├── audit_batches.py                  # Reports per-batch counts and prompt variants
│   ├── fix_system_prompts.py             # Replaces non-canonical prompts with canonical ones
│   └── populate_equivalents.py           # Identifies unmapped English fallback turns
├── canonical_prompts.py                  # Single source of truth for 4 system prompts
├── merge_batches.py                      # Merges raw batches → training_data.json
├── recombine_app_user_language.py        # Deterministic EN→HA mirroring
├── compile_to_jsonl.py                   # JSON → JSONL conversion for HF datasets
├── validate_batch.py                     # Schema validation tool
├── fieldwork_seeds.xlsx                  # Original fieldwork data collection sheet
├── hausa_equivalents.json                # 514 human-verified English→Hausa mappings
├── zaurelink_training_data.json          # 524 merged EN-config records
├── zaurelink_training_data_recombined.json  # 1,048 records (EN + HA mirrored)
└── zaurelink_training_data.jsonl         # Final training file (1,048 JSONL lines)
```

### Record Schema

Each record is a multi-turn conversation:

```json
{
  "id": "MKT-EN-0001",
  "environment": "market",
  "app_user_language": "en",
  "conversations": [
    {"role": "system", "content": "You are a translation engine operating in Market Mode..."},
    {"role": "user", "content": "Assalamu alaikum", "spoken_language": "ha"},
    {"role": "model", "content": "Assalamu alaikum"}
  ],
  "domain_tags": ["greeting"],
  "source": "fieldwork_seed",
  "seed_scenario_id": null,
  "verified": true,
  "verifier": "gemini_backtranslation"
}
```

---

## Repository Layout

```
zaurelink-ai/
├── README.md                  ← You are here
├── notebooks/
│   ├── 01_fine_tuning.ipynb
│   ├── 02_diarization_experiment.ipynb
│   └── 03_litertlm_export.ipynb
├── dataset/
│   ├── raw_batches/
│   ├── scripts/
│   ├── (processing scripts and data files)
│   └── zaurelink_training_data.jsonl
└── docs/
    ├── replication_guide.md   # Full step-by-step replication document
    └── MODEL_CARD.md          # Hugging Face model card
```

---

## Compliance & Licensing

- **This repository's code:** [MIT License](../zaurelink-mobile/LICENSE) — OSI-approved, no restriction on commercial use.
- **Gemma 4 E2B:** [Google Gemma Terms of Use](https://ai.google.dev/gemma/terms).
- **Training tools:** Unsloth (Apache 2.0), Hugging Face TRL/PEFT/Transformers (Apache 2.0), LiteRT-Torch (Apache 2.0).
- **Fine-tuned model artifact:** Hosted on [Hugging Face](https://huggingface.co/israel-ayeni/ZaureLink) under Apache 2.0.

For full licensing details including the MMS-TTS Hausa CC-BY-NC-4.0 disclosure, see the [mobile README license section](../zaurelink-mobile/README.md#license).
