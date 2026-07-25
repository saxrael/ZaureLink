---
license: apache-2.0
language:
- ha
- en
base_model:
- google/gemma-4-E2B-it
pipeline_tag: audio-to-audio
---
---
language: 
  - ha
  - en
tags:
  - gemma
  - litert
  - translation
  - qlora
  - android
  - edge-ai
license: apache-2.0
---

# ZaureLink Translator v1

**ZaureLink Translator v1 is a model trained on a custom Hausa-English dataset for offline mobile translation, based on Gemma 4 E2B.** 
*Gemma is a trademark of Google LLC.*

## Model Overview
This is a highly specialized, parameter-efficient fine-tune (QLoRA) of the Gemma 4 E2B model, explicitly engineered to run **100% offline on low-end Android hardware** (Snapdragon 4-series, ≤4GB RAM). It powers the **ZaureLink** speech-to-speech translation app, submitted for the *Build With Gemma (GDG on Campus ABU Zaria)* Hackathon.

The model has been compiled into a `.litertlm` artifact using Google's `litert-torch` toolchain with the `dynamic_wi4_afp32` quantization recipe, compressing it to ~2.4GB while bundling the embedder and runtime.

## Intended Use
The model serves as the core translation engine for a two-way, real-time conversation between English-dominant and Hausa-dominant speakers. It operates under two strict contextual modes:
*   **Market Mode:** Tuned for loud, unstructured environments. It understands Northern Nigerian bargaining shorthand, currency slang (e.g., *dari biyar* resolving to 500 naira), and local measurement units (*mudu*, *tiya*).
*   **Campus Mode:** Tuned for academic, transport, and clinical interactions. Crucially, it translates clinical symptom descriptions accurately (e.g., *jikina yana zafi* -> *my body aches*) and handles Keke Napep transport negotiations.

## Architectural Features
To meet severe latency (≤1.5s) and RAM (≤1.5GB active footprint) constraints, the following architectural choices were baked into the model export and Android integration:
1.  **Code-Switching Pass-Through:** The model natively detects the spoken language. If the speaker uses a language the listener already understands, the model relays it unchanged, preventing redundant or confusing translations.
2.  **Bounded Context Memory:** The LiteRT runtime limits the KV cache to 1536 tokens. The model is trained to resolve references (e.g., translating "reduce *it*" based on a price stated three turns earlier) within an 8-turn sliding conversation window.
3.  **Hardware Audio Routing:** Speaker diarization is handled mechanically by the Android app's dual-mic (earpod/loudspeaker) routing, allowing the model to focus purely on semantic translation.

## Training Data & Setup
*   **Base Model:** `google/gemma-4-E2B-it`
*   **Dataset:** ~950 hand-curated, multi-turn conversational records mimicking real-world ABU Zaria market and campus interactions.
*   **Fine-tuning:** Unsloth QLoRA (Rank 16, Alpha 16) targeting all linear layers. Trained with `weight_decay=0.05` and a 5% dropout to prevent overfitting on the small dataset.
*   **Export:** Natively lowered to MLIR and compiled via `litert-torch` using CPU-optimized, single-thread streaming to bypass 30GB RAM ceilings during compilation.

## How to use in Android (LiteRT)
This model is designed to be consumed by the Android `ai-edge-litert` libraries. 
Provide the `.litertlm` file to the LiteRT-LM engine with the following configuration:
```json
{
  "model_path": "zaurelink-translator-v1.litertlm",
  "backend": "cpu_xnnpack",
  "num_threads": 4,
  "mmap_model_weights": true,
  "context_length": 2048,
  "cache_length": 1536
}