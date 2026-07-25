# ZaureLink — AI Engineering Replication Guide
### Fine-Tuned Gemma 4 E2B Pipeline for Offline Hausa↔English Speech Translation
**Prepared for:** Internal engineering record and hackathon Writeup support
**Track:** Gemma for Local Languages & Literacy — Build With Gemma: GDG on Campus ABU Zaria
**Scope:** AI/ML, data pipeline, and model export only. Client-side/mobile integration is documented separately by the mobile developer; this guide references the TRD where a hand-off point touches both sides.

---

## 0. Purpose and How to Read This Document

An engineer picking this document up cold, with no memory of the build, should be in a position to reproduce the dataset, the fine-tuning run, and the exported artifact end to end, without guessing at a single environment variable. This is the bar this document is written against, and it is why several sections below spend time on decisions which got reversed along the way, rather than presenting only the final state. A design which looks obvious in hindsight often is not obvious at 2am on day 6 of an 11-day build, and a reader who does not see the wrong turn will not understand why the final turn was taken.

Three things shaped this build from the start: a fixed submission deadline, a two-person team, and a hard on-device constraint set (≤1.5s latency, ≤1.5GB active RAM, ≤50MB initial APK, on a ≤4GB-RAM Snapdragon-4-series floor). Every architectural choice in this document answers to one of those three pressures, and this is noted explicitly where relevant.

---

## 1. System Overview and Hard Constraints

ZaureLink is an offline, bidirectional Hausa↔English speech-to-speech translator, built for two everyday Northern Nigerian settings — Market Mode and Campus Mode — where a bilingual student needs to communicate in real time with a Hausa-only or English-only counterpart, often in loud, uncontrolled surroundings.

The core model is `google/gemma-4-E2B-it`, deployed on-device through LiteRT-LM, with zero network dependency once the model file is downloaded. Development and fine-tuning happen through Hugging Face Transformers in Colab; production inference happens through LiteRT-LM on the phone.

The non-negotiable ceilings, carried across the PRD and TRD without exception, are these:

| Constraint | Ceiling | Governing mechanism |
|---|---|---|
| End-to-end latency (NFR-01) | ≤1.5s, measured from end of utterance | Per-stage timing in a latency ring buffer; token caps bound inference time |
| Active RAM (NFR-02) | ≤1.5GB | `Mmap` weight loading + bounded `cache_length`/`context_length` + 8-turn sliding window |
| Initial app size (NFR-03) | ~50–70MB (AAB) / ~255MB (Universal APK) | Model shipped separately, post-install, never bundled. Direct site download uses universal multi-arch APK. |
| Network dependency (NFR-04) | Zero, post-download | All components on-device |
| Target hardware floor | ≤4GB RAM, Snapdragon 4-series | CPU-only XNNPACK inference path, no GPU assumption |

One clarification worth stating plainly, because it looked contradictory on paper before it got resolved: the on-disk model file sits around 1.7–2GB, against a 1.5GB RAM ceiling. These are not the same budget. `Mmap` loading means the file is demand-paged from disk rather than loaded whole into process memory, so resident RAM during inference is governed by the KV cache, activation buffers, and runtime overhead — not the file size on disk. Anyone reproducing this build who skips `mmap_model_weights: true` at runtime will not get this budget arithmetic to hold, no matter how correct the quantization is.

The final quantization recipe used for the deployment artifact is `dynamic_wi4_afp32` (INT4 dynamic weights, FP32 activations), compiled through `litert-torch export_hf` with `--externalize_embedder`, `--cache_length=1536`, `--prefill_lengths=128,256`, `--bundle_litert_lm`, and `--use_jinja_template`.

---

## 2. Architectural Evolution: From Fixed-Direction to Session-Level Language Choice

This is the single biggest structural change in the project, and it is documented here in full because a reader who only sees the final four-prompt design will not understand why it looks the way it does, and might rebuild the earlier, weaker version by accident.

**The original assumption.** Early in the build, the system carried a single `Direction` enum, and it silently assumed the app user is always English-dominant, with the counterpart always Hausa-dominant. This is wrong on its face — the PRD is explicit: a student's own required language is a genuine per-session choice, not a fixed default — but the assumption sat unchallenged for a while because it matched the most common demo scenario the team had in mind.

**The audit.** Once challenged, the `Direction` enum turned out to be conflating three separate concepts under one name: which physical mic channel is active, which language the app user requires as output, and which language was spoken on a given turn. Collapsing three concepts into one enum is the kind of shortcut which works until the moment a Hausa-dominant app user tries to use the app, at which point the whole translation logic breaks in a direction nobody tested.

**The correction.** `Direction` is retired outright. In its place:
- `ActiveChannel` — mechanical, per-turn, tracks which mic (earpod vs. phone mic) is live.
- `AppUserLanguage` — session-level, chosen once at `startConversation()`, fixed for the session's duration.
- Actual spoken language — detected by the model itself, per turn, never passed in as a parameter.

Required output language for a given turn is now derived, not assumed: if the earpod channel is active, the listener is the other party, and they require the opposite of `AppUserLanguage`; if the phone mic is active, the listener is the app user, and they require `AppUserLanguage` directly. The model checks what was spoken against what the derived listener requires — if the two already match, it relays the turn unchanged (a pass-through); if they do not, it translates.

**A dead end worth naming.** Between the original design and the final one, there was a middle attempt at collapsing the four direction-locked prompts into one prompt driven by session config, specifically to avoid a system-prompt swap mid-conversation forcing a model/session reload. This middle attempt solved the reload problem, but for the wrong reason — the real fix was making the prompts session-fixed rather than per-turn, which the final four-prompt design already achieves without needing a collapse. The team ended up back at four prompts, but for a structurally different reason than the original four: not because direction was fixed, but because `Environment × AppUserLanguage` genuinely produces four stable, session-scoped configurations.

**Consequence for training data.** This is not a prompt-wording change alone. A system prompt describing pass-through behaviour does not teach a fine-tuned model to produce it — the model only learns this from real training examples, for each of the four `Environment × AppUserLanguage` combinations separately, because the exact same spoken content is a translation case under one configuration and a pass-through case under another. Section 4 below (A.2b, deterministic recombination) exists specifically to generate the second half of this pair without needing a second fieldwork pass.

**Downstream, this reached the mobile developer already.** The updated integration message — retiring `Direction`, introducing `ActiveChannel`/`AppUserLanguage`, and the four system prompts — has been sent, and the JSON payload contract between the translation provider and the mobile app is fixed in the TRD. An independent engineer picking up the mobile side should treat the TRD as the current source of truth on this point, not the PRD's earlier language.

---

## 3. Multi-Speaker Audio Handling: An Empirically Closed Question

A tempting design early on was to lean on Gemma 4's documented diarization capability, so overlapping speech from two people could be separated without needing two microphones. This was investigated properly, and rejected on evidence, not assumption — worth walking through in full, because a future engineer might otherwise re-open a question which already has a firm answer.

**What is documented, and where it stops applying.** Gemma 4's diarization/speaker-separation capability is real and documented — for Gemma 4 12B specifically, which uses a newer, encoder-free audio pathway. Gemma 4 E2B, the model this project uses, still runs an older, separate conformer-based audio encoder (roughly 305M parameters), and nothing in the documentation confirms E2B inherits the 12B's diarization behaviour. Treating a capability documented for one model size as automatically true for a smaller sibling model is exactly the kind of assumption worth testing before it gets built on.

**The empirical test.** A real overlapping-speech audio sample was loaded directly through `transformers` in Colab (AI Studio's own interface does not expose E2B as a selectable model, so the test had to go around it, loading `google/gemma-4-E2B-it` directly instead). Two gotchas were designed around going in, rather than discovered mid-session: the unquantized checkpoint sits at roughly 10.2GB, which needs a T4's full 16GB VRAM headroom to load comfortably, and Gemma 4 carries a "thinking" mode which prepends reasoning content ahead of its actual answer — any logging code has to explicitly separate the two, or the reasoning trace silently contaminates the transcription being evaluated.

The test itself ran a fixed, honestly-worded prompt against the sample, deliberately written to avoid leading the model toward a particular answer:

```
You are given an audio clip. Transcribe exactly what you hear. If more than one voice is
present, state how many distinct voices you can identify, and transcribe each one separately
if you're able to. If you can only clearly make out one voice, say so plainly and transcribe
only that one. Do not guess at words or speakers you can't confidently identify — mark any
unclear portion as [unclear] rather than filling it in with a best guess.
```

Under this baseline prompt, the output collapsed to one detected voice where two were present, with degenerate, looping tokens (a repeated, truncated phrase pattern — "Ina shiga, ina shig..." — a classic token-looping failure, not a one-off transcription slip). A second variant, appending a semantic-focus instruction to the same prompt in an attempt to nudge the model toward prioritizing the voice engaged in commercial exchange (prices, currency, bargaining terms) over background chatter, was tested against the identical sample — and it also collapsed to one voice, while measurably worsening the token-looping and latency rather than improving either.

**The decision, and why it is closed rather than merely deprioritized.** Attempting genuine blind-source separation on a single phone microphone, inside an 11-day build with a two-person team and an already-tight 1.5s/1.5GB budget, is the wrong use of scarce time against an uncertain payoff — and even where diarization is real, it solves an easier problem than true simultaneous overlap, since diarization accuracy itself degrades on overlapping speech in independent benchmarks. The team relies entirely on the app's existing two-microphone hardware split, which already separates the two conversation participants without needing a model to do it, plus near-field RNNoise/WebRTC-NS and Silero VAD for residual bystander noise. The semantic-focus prompt addition is not in the system prompt, not in the training data, and not applied at runtime — full stop. If this document is read alongside an older draft which still mentions it as a live possibility, this older draft is stale.

**Pipeline parameters, for reproduction:** DSP frame size 20ms (320 samples at 16kHz), VAD window 32ms (512 samples at 16kHz, Silero VAD), speech buffer committed only after N consecutive speech frames and M consecutive silence frames (end-of-utterance rule, guards against fragment-triggering on short noise bursts like a slammed door or a shout).

---

## 4. Data Pipeline, Fieldwork to Training File

The dataset behind this fine-tune is built on a hybrid principle: real fieldwork is treated as the scarce, non-scalable resource, and cloud-LLM augmentation (Gemini via AI Studio) as the scalable multiplier. A small number of real seed exchanges, recorded on the ground, get expanded into many training-ready variants — protecting the timeline without making the dataset synthetic top to bottom.

### 4.1 Fieldwork Collection (Part A.1)

Real Hausa↔English phrase pairs were recorded across the two target environments, Market and Campus, using a structured Google Sheet. The confirmed target was 15–25 real seed pairs per environment as a minimum, and fieldwork collection has met or exceeded this floor at time of writing. Within this count, 4–6 multi-turn scenarios per environment were prioritized deliberately, since single-exchange seeds teach translation accuracy alone, while only multi-turn seeds teach reference resolution and speaker-register consistency across a conversation — a requirement the PRD names directly (FR-10), and one a dataset built entirely from isolated sentences would silently fail to teach, no matter how many single-line examples it contained.

Two columns were added mid-build, once the `AppUserLanguage` correction (Section 2) landed: `original_spoken_language` (`ha` or `en`, marking which language a given line was spoken in) and `hausa_equivalent_natural` (filled only for English-original lines, giving the natural Hausa equivalent a fluent speaker would use). These two columns are what let the deterministic recombination script below tell lines apart structurally, instead of relying on a prose note a script cannot parse.

Multi-turn scenarios are capped at 8 exchanges (16 lines total), matching the runtime's `conversation_window_turns` sliding-window limit exactly — training the model on a longer conversational shape than the app will ever hold in memory would teach a pattern the deployed system will never use.

### 4.2 Synthetic Expansion and Verification (Part A.2a)

Each fieldwork seed is expanded through Gemini, via AI Studio, into roughly 10–15 paraphrased variants, generating both translation directions as separate records — one where the Hausa phrase becomes natural English, one where the English gloss becomes an equally natural, idiomatic Hausa phrase — rather than a mechanical word-for-word reversal in either direction. Every generated record in this stage is tagged `app_user_language: "en"`; the mirrored `"ha"` configuration is deliberately not generated here, and is produced separately, deterministically, in the next stage.

A verification pass sits behind this step for a specific reason: a cloud LLM's idea of "market Hausa" is not the same as what gets recorded at an actual Samaru market stall, and an unverified synthetic set risks optimizing the model toward Gemini's approximation of the domain rather than the domain itself. Records are marked `verified: true` only after passing this back-translation check or a manual review pass by the team's bilingual member.

### 4.3 Deterministic Recombination (Part A.2b)

This stage is a plain Python script, deliberately not an AI Studio prompt. Everything up to A.2a uses Gemini for paraphrasing, which is appropriate there because it operates inside an already-verified semantic envelope. Recombining a verified record into the opposite `app_user_language` is a different kind of operation — pure reassignment of text already checked for correctness — and running this reassignment through an LLM again would reintroduce exactly the drift risk the whole verification step exists to prevent. A script either performs the reassignment correctly and deterministically, or it does not run; there is no "close enough" version of this step.

The script runs on the fully merged output of A.2a (`dataset/zaurelink_training_data.json`), not on raw seeds, so the benefit of A.2a's full paraphrase volume carries through to both language configurations rather than only the original seed count. Every "app-user-spoke-English, translated-to-Hausa" turn needs its verified Hausa equivalent (the `hausa_equivalent_natural` column from A.1) to mirror correctly; where this column was left blank, the script falls back to a weaker pass-through variant instead, and flags every fallback it uses explicitly, so the team knows exactly which records are full-strength and which are not, rather than this distinction quietly disappearing into the output file.

### 4.4 Compile to Training Format (Part A.2c)

The final compile step converts the recombined JSON array into JSONL, matching the idiom Hugging Face `datasets` expects, and because a JSONL file damaged partway through loses only the affected line, where a damaged JSON array loses the entire file. Pretty-printed JSON is kept for every authoring and verification stage before this point, since human readability matters when the team's only bilingual speaker is the one spot-checking correctness by eye.

### 4.5 File and Folder Lineage

For anyone reproducing this pipeline from scratch, the exact chain of files and scripts is:

```
dataset/raw_batches/batch_NNN.json          (A.2a output, per batch)
  → python3 dataset/merge_batches.py
dataset/zaurelink_training_data.json        (merged A.2a output)
dataset/hausa_equivalents.json              (manually built lookup, keyed by exact English text,
                                              only for multi-turn lines where original_spoken_language = en)
  → python3 dataset/recombine_app_user_language.py
dataset/zaurelink_training_data_recombined.json
  → python3 dataset/compile_to_jsonl.py
dataset/zaurelink_training_data.jsonl       (the only file the fine-tuning notebook should load)
```

### 4.6 Balance Correction Across Environment × Language Cells

Once the recombination stage produced a full four-way split (`Market×en`, `Market×ha`, `Campus×en`, `Campus×ha`), an explicit balance check ran across these four cells, since an uneven fieldwork ratio would otherwise carry straight through into training without anyone noticing. Correction was applied per cell rather than through one blanket rule: some over-represented cells were trimmed down, and some under-represented cells were topped up with fresh synthetic seeds through A.2a, chosen case by case rather than forcing every cell through the same fix. This hybrid approach is worth defending explicitly in any Writeup, since a single global correction (pure undersampling, or pure oversampling) would have been simpler to describe but would have thrown away real fieldwork data in one direction, or diluted the fieldwork-to-synthetic ratio too far in the other.

### 4.7 Fallback Path, if Fieldwork Had Not Materialized

Documented for completeness, though not the path this build ended up needing: had recording sessions failed to happen in time, the pipeline was designed to degrade gracefully rather than stall, falling back to Gemini role-playing both sides of a market or campus conversation, grounded in the same scenario descriptions already written up, verified through the same back-translation pass, and tagged `"source": "synthetic_permutation"` with `"seed_scenario_id": null` to keep the provenance honest.

---

## 5. Fine-Tuning and Export: The Colab-to-Kaggle-TPU Migration

This section covers the part of the build which does not appear in any of the planning documents at all, and is the one a future engineer is most likely to get stuck on without a written account of it.

### 5.1 Planned Environment

The original plan, carried through the AI Strategy Document, was a single-environment build: Google Colab, free-tier T4 GPU, Unsloth's QLoRA implementation, Hugging Face TRL's `SFTTrainer`, and a `litert-torch export_hf` step at the end to produce the final `.litertlm` artifact — all inside the same notebook, on the same machine.

### 5.2 The Fine-Tuning Configuration, as Run

The Colab notebook, on the section which ran end to end, used:
- `unsloth.FastLanguageModel`, `max_seq_length=2048`, `dtype=None` (auto-detected), `load_in_4bit=True`
- LoRA configuration: `r=16`, `target_modules=["q_proj","k_proj","v_proj","o_proj","gate_proj","up_proj","down_proj"]`, `lora_alpha=16` (alpha set equal to rank, standard practice for this scale of task), `lora_dropout=0`, `bias="none"`, `use_gradient_checkpointing="unsloth"`, `random_state=42`, `use_rslora=False`, `loftq_config=None`
- Model authentication through `google.colab.userdata.get('HF_TOKEN')`

### 5.3 The Dependency Wall

On the first import line of the Unsloth section (`from unsloth import FastLanguageModel`), Colab threw a `typing_extensions.Sentinel` `ImportError`. The cause: `pydantic_core`, pulled in as a dependency by Unsloth, requires `typing_extensions>=4.14.0` (the version which introduced `Sentinel`), and Colab's pre-installed version predates it. The fix is `pip install --upgrade typing_extensions pydantic pydantic-core`, followed by a mandatory Colab runtime restart (Runtime → Restart session) — a plain reinstall alone does not resolve an import which has already failed inside a live kernel. This fix works, but has not yet been written back into the fine-tuning notebook's Section 1 (Environment Setup) as a defensive check, so anyone re-running this notebook fresh should expect to hit this same wall on the first attempt, and should apply the fix proactively rather than waiting for the error.

### 5.4 The Memory Wall, and the Move Off Colab

Training itself completed on Colab. The wall came later, at the export and quantization step. Colab's free-tier RAM was not sufficient to carry `litert-torch export_hf` through to completion — the conversion step needs to hold the merged, full-precision checkpoint and the quantization process simultaneously, and free-tier Colab's memory ceiling was not built with this step in mind.

The intermediate, fine-tuned artifact (merged LoRA adapter weights, tokenizer, config) was moved off Colab and carried across through a Kaggle Dataset, rather than the whole environment being rebuilt from scratch on the new platform — dependencies and setup were re-established fresh on Kaggle, but the trained weights themselves travelled as a dataset upload rather than being retrained.

Kaggle's standard instance, at 30GB RAM, still was not enough for the conversion step to complete. The resolution was switching to a Kaggle TPU v5e-8 instance specifically for this stage — not because the export step needs a TPU's compute characteristics, but because the TPU instance tier came with enough memory headroom to carry the conversion through. Once on the TPU instance, the export command itself ran unchanged — same `litert-torch export_hf` invocation, same `dynamic_wi4_afp32` recipe, same flags (`--externalize_embedder`, `--cache_length=1536`, `--prefill_lengths=128,256`, `--bundle_litert_lm`, `--use_jinja_template`) as originally planned for Colab. The wait to be provisioned a TPU instance on Kaggle was itself a real cost against the 11-day runway, and is worth budgeting for explicitly if this pipeline is reproduced under a similarly tight deadline.

**The practical takeaway for reproduction:** budget for the export/quantization step to need more memory than the training step itself, and treat "Colab for training, a higher-memory environment for export" as the expected shape of this pipeline, not a contingency. A Kaggle TPU instance is one working option; any environment with sufficient RAM headroom for holding a merged full-precision checkpoint plus the quantization process at once should serve the same purpose.

### 5.5 Notebook Completion Status

Sections 1–2 (environment setup, data loading and chat-template formatting) have been re-run clean with the `typing_extensions` fix applied. Section 3 onward (QLoRA initialization through SFTTrainer, adapter validation, merge, and export) is confirmed to have completed, given the artifact exists and has been carried through to Kaggle and exported successfully. All six notebook sections are therefore confirmed complete, and B.2 Section 1 (the notebook's environment-setup prompt) should still be hardened with the defensive check described in 5.3, so a fresh regeneration of this notebook from the AI Strategy Document does not reproduce the same import failure from a clean start.

---

## 6. Model Hand-off Protocol

The competition's public-repository requirement sits in tension with a ~1.7–2GB model file — a git repository is the wrong home for an artifact of this size, regardless of hackathon rules, so the hand-off protocol keeps the code repository lean:

1. The final `.litertlm` file is hosted on a public Hugging Face model repository, not committed into the GitHub code repository — this mirrors exactly how the base Gemma 4 models themselves are distributed, and satisfies the competition rule: anything linked from the Writeup must be publicly accessible with no login or paywall.
2. A SHA-256 checksum of the exported file is generated and committed to the code repository (a few bytes, not the model itself), alongside a short `MODEL_CARD.md` documenting the base model, LoRA rank/alpha, dataset composition (fieldwork-versus-synthetic split, single-exchange-versus-multi-turn record counts), the quantization recipe, and known limitations, including the 8-turn bounded conversation window.
3. Hand-off to the mobile side happens through a single updated config value — the `Gemma4FineTunedProvider` model URL plus its checksum — with no other integration code changes required, since the interface contract between the provider and the app was fixed at the TRD stage.
4. The artifact filename carries a version/date suffix (`zaurelink-gemma4-e2b-lora-v1.litertlm`), so a failed last-minute retrain never silently overwrites a working demo-day build. The previous version's link stays available as a rollback until a new version is confirmed working end-to-end on-device.

---

## 7. Compliance and Licensing

The Winner's Obligations require any winning team to grant a non-exclusive, worldwide, royalty-free license under an OSI-approved open-source license, with no restriction on commercial use of the underlying code or model. This has one direct consequence for the dependency stack: any component the pipeline leans on for the training or export path — Unsloth, the Colab/Kaggle stack, and anything named in the eventual public code repository — needs to sit inside this same OSI-approved boundary, or the team cannot fulfil the obligation cleanly if it places in the competition.

This question was raised with the competition organizers directly, given the ambiguity of applying a source-code license requirement to a training pipeline rather than to the shipped app code alone. The organizers responded, and the team is following this guidance. Anyone continuing this build after the hackathon should treat the organizer's answer as the governing interpretation, and should confirm it is still current before relying on it for a future, non-hackathon release of the same pipeline.

---

## 8. Evaluation Against the Hackathon Rubric

The rubric weights four criteria: Gemma Integration (30%), Innovation & Impact (30%), Functionality (20%), and Presentation & Writeup (20%). This section maps the engineering work above onto each one directly, rather than leaving the connection implicit.

**Gemma Integration (30%).** The model is not a wrapper around an off-the-shelf API call — it is fine-tuned on a purpose-built dataset traceable to real fieldwork, deployed on-device through LiteRT-LM with its native audio-input modality preserved through the merge and export. The three-tier provider strategy (Mock, Baseline, Fine-Tuned) means the fine-tuned adapter drops into a real, already-integrated interface, rather than existing only as an isolated notebook.

**Innovation & Impact (30%).** The `ActiveChannel`/`AppUserLanguage` split solves a real usability gap the original fixed-direction design would have shipped with unnoticed — a Hausa-dominant app user is not a hypothetical edge case in this context, and the correction was made before it reached a demo audience, not after. The hybrid fieldwork-plus-synthetic-augmentation dataset strategy, and the deterministic (non-AI) recombination step specifically, are defensible engineering choices worth naming explicitly in the Writeup, since they show a team protecting against a real, named failure mode (LLM-introduced linguistic drift) rather than trusting a synthetic pipeline blindly.

**Functionality (20%).** This is the rubric criterion still carrying an open item at time of writing. No local validation numbers exist yet for translation quality or on-device latency/RAM. A plan is confirmed to measure both on real target hardware before submission, rather than relying on a Colab or Kaggle proxy figure, since a proxy measurement on cloud hardware would not honestly represent the ≤1.5s/≤1.5GB ceilings the app is bound by on a Snapdragon-4-series device. Until this on-device run completes, any Functionality claim in the Writeup should be phrased as a design-level guarantee (bounded token counts, `Mmap` loading, sliding-window memory) rather than a measured result, and updated with real figures the moment they exist.

**Presentation & Writeup (20%).** The Kaggle Writeup (Deliverable 5) is roughly 1,019 words against a 1,500-word limit, and currently still describes the old fixed-direction flow rather than the `ActiveChannel`/`AppUserLanguage` architecture documented in Section 2 above. It has not been submitted yet, which is the reason this is listed as an action item rather than a completed gap — see Section 9 below.

---

## 9. Open Items at Time of Writing

For anyone picking this project up next, these are the loose ends still open, in the order they matter most:

1. **On-device validation (Functionality, 20%)** — latency and RAM measurement on real target hardware is planned but not yet run. This is the single biggest remaining risk against the rubric, since two of the four scoring criteria touch functionality either directly or through Gemma Integration.
2. **Kaggle Writeup update (Presentation, 20%)** — not yet submitted; needs the `ActiveChannel`/`AppUserLanguage` architecture and the Colab→Kaggle→TPU migration folded in before submission, both of which currently exist only in this document and the team's own memory of the build.
3. **B.2 Section 1 hardening** — the fine-tuning notebook's environment-setup prompt has not yet been updated with a defensive `typing_extensions`/`pydantic` compatibility check, so regenerating this notebook from the AI Strategy Document as written today would reproduce the same import failure described in Section 5.3 above, even though the live notebook itself has already run past it.
4. **OSI dependency question** — resolved by organizer guidance, team following it; worth re-confirming if this pipeline is reused past the hackathon submission window, since the guidance was given in the context of this specific competition.

Notebook Sections 1–2 and the multi-speaker empirical test (Section 3 above) are both confirmed complete and are not listed here as open.

---

*End of document. This guide should be read alongside the PRD and TRD for anything touching mobile integration, functional requirements, or the exact system-prompt text — those two documents remain the authoritative source for their respective domains, and this guide defers to them wherever the two might disagree.*
