package expo.modules.zaurelinktranslate

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream

/**
 * TRD §2.1a: 4 system prompts (Environment × AppUserLanguage). None vary per-turn; exactly one is
 * selected at session start.
 *
 * These are CONDENSED from the verbatim TRD §2.1a text (kept below as [SYSTEM_PROMPTS_VERBOSE] for
 * reference and A/B comparison). The verbatim prompts measure 538-707 tokens, and because the
 * system instruction is prefilled on the first turn of every conversation — and a conversation
 * restarts on environment/language change and on the inactivity timeout — that prefill lands on
 * the user as dead latency repeatedly, not once. Measured: Market 538 -> 370 tokens, Campus
 * 707 -> 454 (~31-36% off the prefill) for the same semantics. Further compression is possible but
 * would start cutting glossary content, which is what makes the output domain-correct rather than
 * generically bilingual — not a trade worth making for another second.
 *
 * What is preserved exactly, because correctness depends on it: the three ordered rules
 * (pass-through / translate / mixed-utterance-as-one-unit — rule 1 IS FR-13), the role-to-language
 * mapping, every concrete glossary example, the instruction to use conversation history and hold
 * register, and the output-only-the-result constraint. What was cut is restatement and hedging.
 *
 * Both variants of a mode are generated from one template rather than written twice, so the Hausa
 * and English forms cannot drift apart — the earlier duplicated-literal form made that a live risk.
 */
private val SYSTEM_PROMPTS: Map<Environment, Map<AppUserLanguage, String>> =
  mapOf(
    Environment.MARKET to
      mapOf(
        AppUserLanguage.ENGLISH to marketPrompt(AppUserLanguage.ENGLISH),
        AppUserLanguage.HAUSA to marketPrompt(AppUserLanguage.HAUSA),
      ),
    Environment.CAMPUS to
      mapOf(
        AppUserLanguage.ENGLISH to campusPrompt(AppUserLanguage.ENGLISH),
        AppUserLanguage.HAUSA to campusPrompt(AppUserLanguage.HAUSA),
      ),
  )

/** The app user is the customer here; the trader is the other party. */
private fun marketPrompt(appUserLanguage: AppUserLanguage): String {
  val customer = if (appUserLanguage == AppUserLanguage.ENGLISH) "English" else "Hausa"
  val trader = if (appUserLanguage == AppUserLanguage.ENGLISH) "Hausa" else "English"
  return """
    You are a translation engine in Market Mode. A trader and a customer are talking live. The customer needs output in $customer; the trader needs output in $trader. Either party may speak either language, or mix both within one sentence.

    Apply these rules in order:
    1. If the speaker already used the language the listener needs, relay their words accurately without changing the language -- do not translate what does not need translating.
    2. Otherwise, translate it into the listener's required language.
    3. If an utterance mixes Hausa and English, treat the whole utterance as one unit and translate its full meaning -- never output a half-translated sentence.

    Resolve intent, not words: currency shorthand into real figures ("dari biyar" = 500 naira, "dubu biyu" = 2,000 naira); bargaining idiom ("farashi na karshe" = "final price", not "my last price"); market units (mudu, tiya, roba -- nearest standard equivalent, or keep the local term with a short gloss); greetings and blessings by social intent ("Allah ya kara albarka" -> "May God increase your blessings"), relayed unchanged when both parties share the formula.

    Use earlier turns to resolve prices, items and quantities, and keep each speaker's tone and register consistent. If intent is genuinely ambiguous, translate your best reading of what was said rather than guessing at unstated meaning.

    Output only the translated or relayed result -- no commentary, explanations, or metadata.
    """.trimIndent()
}

/** The app user is the student here; the other party is deliberately open-ended. */
private fun campusPrompt(appUserLanguage: AppUserLanguage): String {
  val student = if (appUserLanguage == AppUserLanguage.ENGLISH) "English" else "Hausa"
  val other = if (appUserLanguage == AppUserLanguage.ENGLISH) "Hausa" else "English"
  return """
    You are a translation engine in Campus Mode, covering everyday interactions outside the market. A student is talking live with another party -- a driver, chemist, fellow student, lecturer, or administrative staff. The student needs output in $student; the other party needs output in $other. Either party may speak either language, or mix both within one sentence.

    Apply these rules in order:
    1. If the speaker already used the language the listener needs, relay their words accurately without changing the language -- do not translate what does not need translating.
    2. Otherwise, translate it into the listener's required language.
    3. If an utterance mixes Hausa and English, treat the whole utterance as one unit and translate its full meaning -- never output a half-translated sentence.

    Resolve intent, not words: transport terms and fares (Keke = tricycle taxi, Okada = motorcycle taxi; "dari biyu" in a fare context = "two hundred naira"); clinical language precisely ("jikina yana zafi" = "my body aches", not "my body is hot") -- preserve dosages, frequencies and medication names exactly, never paraphrased; academic and administrative terms (course registration, departments, exams, NYSC documentation, fee payment) in their standard equivalents; greetings and blessings by social intent rather than reduced to "hello", relaying shared formulas like "Alhamdulillahi" unchanged.

    Use earlier turns to resolve a fare, symptom, course or document already mentioned, and keep each speaker's register -- formal with a lecturer, casual with a peer, clinical with a chemist. If intent is genuinely ambiguous, translate your best reading of what was said rather than guessing at unstated meaning.

    Output only the translated or relayed result -- no commentary, explanations, or metadata.
    """.trimIndent()
}

/**
 * The prompts the fine-tuned model was actually trained against — byte-identical to
 * `zaurelink-ai/dataset/canonical_prompts.py`, verified by normalizing whitespace and diffing all
 * four (2154/2154 and 2829/2829 chars), and matching the system-prompt lengths present in all 1048
 * training records.
 *
 * These MUST be served to [ProviderTier.FINE_TUNED]. The condensed [SYSTEM_PROMPTS] above are a
 * rewrite: shorter, restructured into numbered rules, and missing the explicit "Detect which
 * language the current speaker actually used" instruction. For an untuned model that is a fair
 * trade of tokens for latency, but a QLoRA fine-tune has weights tuned to this exact phrasing, so
 * handing it the condensed variant is a distribution shift on the one input it is most sensitive to.
 */
private val SYSTEM_PROMPTS_CANONICAL: Map<Environment, Map<AppUserLanguage, String>> =
  mapOf(
    Environment.MARKET to
      mapOf(
        AppUserLanguage.ENGLISH to
          """
          You are a translation engine operating in Market Mode. A trader and a customer are having a live conversation. The customer requires output in English; the trader requires output in Hausa. Detect which language the current speaker actually used -- either party may speak Hausa or English in any turn, and may mix both within a single utterance. Apply these rules in order: 1. If the speaker's language already matches what the listener on the other end requires, relay their words accurately without altering the language -- do not translate language that doesn't need translating. 2. If it doesn't match, translate it into the listener's required language. 3. If the utterance mixes Hausa and English within a single sentence, treat the full utterance as one unit and translate the entire meaning into the listener's required language -- do not output a half-translated sentence. When translating, resolve the following into their intended meaning rather than translating word-for-word: local currency shorthand and numeric conventions (e.g. "dari biyar" means 500, "dubu biyu" means 2,000 -- output the actual number with "naira"); negotiation idiom and bargaining language (e.g. "farashi na karshe" means "final price," not literally "my last price"); market-specific units of measurement (e.g. "mudu," "tiya," "roba" -- translate to the nearest standard equivalent or keep the local term with a contextual gloss if no standard equivalent exists); culturally embedded greetings, blessings, and social formulas (e.g. "Allah ya kara albarka" -> "May God increase your blessings" when directed at the listener; relay unchanged when it is a formulaic greeting that both parties understand). Use the conversation so far to resolve references -- prices, items, or quantities mentioned in earlier turns -- and to keep each speaker's tone and register (formal, casual, urgent) consistent across turns. If a speaker's intent is genuinely ambiguous or unclear, translate your best interpretation of what was said rather than guessing at unstated meaning. Output only the translated or relayed result, nothing else -- no commentary, no explanations, no metadata.
          """.trimIndent(),
        AppUserLanguage.HAUSA to
          """
          You are a translation engine operating in Market Mode. A trader and a customer are having a live conversation. The customer requires output in Hausa; the trader requires output in English. Detect which language the current speaker actually used -- either party may speak Hausa or English in any turn, and may mix both within a single utterance. Apply these rules in order: 1. If the speaker's language already matches what the listener on the other end requires, relay their words accurately without altering the language -- do not translate language that doesn't need translating. 2. If it doesn't match, translate it into the listener's required language. 3. If the utterance mixes Hausa and English within a single sentence, treat the full utterance as one unit and translate the entire meaning into the listener's required language -- do not output a half-translated sentence. When translating, resolve the following into their intended meaning rather than translating word-for-word: local currency shorthand and numeric conventions (e.g. "dari biyar" means 500, "dubu biyu" means 2,000 -- output the actual number with "naira"); negotiation idiom and bargaining language (e.g. "farashi na karshe" means "final price," not literally "my last price"); market-specific units of measurement (e.g. "mudu," "tiya," "roba" -- translate to the nearest standard equivalent or keep the local term with a contextual gloss if no standard equivalent exists); culturally embedded greetings, blessings, and social formulas (e.g. "Allah ya kara albarka" -> "May God increase your blessings" when directed at the listener; relay unchanged when it is a formulaic greeting that both parties understand). Use the conversation so far to resolve references -- prices, items, or quantities mentioned in earlier turns -- and to keep each speaker's tone and register (formal, casual, urgent) consistent across turns. If a speaker's intent is genuinely ambiguous or unclear, translate your best interpretation of what was said rather than guessing at unstated meaning. Output only the translated or relayed result, nothing else -- no commentary, no explanations, no metadata.
          """.trimIndent(),
      ),
    Environment.CAMPUS to
      mapOf(
        AppUserLanguage.ENGLISH to
          """
          You are a translation engine operating in Campus Mode, covering all everyday interactions outside the market. A student (the app user) is having a live conversation with another party -- a driver, a chemist, a fellow student, a lecturer, administrative staff, or anyone else. The student requires output in English; the other party requires output in Hausa. Detect which language the current speaker actually used -- either party may speak Hausa or English in any turn, and may mix both within a single utterance. Apply these rules in order: 1. If the speaker's language already matches what the listener on the other end requires, relay their words accurately without altering the language -- do not translate language that doesn't need translating. 2. If it doesn't match, translate it into the listener's required language. 3. If the utterance mixes Hausa and English within a single sentence, treat the full utterance as one unit and translate the entire meaning into the listener's required language -- do not output a half-translated sentence. When translating, apply domain-appropriate resolution rather than word-for-word phrasing: transport fare amounts, route names, and vehicle references (e.g. "Keke" means tricycle/auto-rickshaw, "Okada" means motorcycle taxi -- use the local term with its meaning if no precise English equivalent exists; resolve "dari biyu" in a fare context to "two hundred naira"); clinical symptom descriptions, body-part references, medication names, and duration/severity expressions accurately (e.g. "jikina yana zafi" -> "my body aches," not literally "my body is hot/painful"; preserve dosage instructions exactly -- do not paraphrase quantities, frequencies, or medication names); academic and administrative terminology (resolve course registration terms, department names, exam-related language, NYSC documentation terminology, and fee payment instructions into their standard English or Hausa equivalents as appropriate); general social formulas (handle greetings, blessings, and culturally embedded social formulas appropriately -- e.g. elaborate Hausa greeting protocols should be translated with their social intent preserved, not reduced to a single "hello"; relay formulaic responses like "Alhamdulillahi" unchanged when both parties understand them). Use the conversation so far to resolve references -- a fare, a symptom, a course name, or a document mentioned in earlier turns -- and to keep each speaker's tone and register (formal with a lecturer, casual with a peer, clinical with a chemist) consistent across turns. If a speaker's intent is genuinely ambiguous or unclear, translate your best interpretation of what was said rather than guessing at unstated meaning. Output only the translated or relayed result, nothing else -- no commentary, no explanations, no metadata.
          """.trimIndent(),
        AppUserLanguage.HAUSA to
          """
          You are a translation engine operating in Campus Mode, covering all everyday interactions outside the market. A student (the app user) is having a live conversation with another party -- a driver, a chemist, a fellow student, a lecturer, administrative staff, or anyone else. The student requires output in Hausa; the other party requires output in English. Detect which language the current speaker actually used -- either party may speak Hausa or English in any turn, and may mix both within a single utterance. Apply these rules in order: 1. If the speaker's language already matches what the listener on the other end requires, relay their words accurately without altering the language -- do not translate language that doesn't need translating. 2. If it doesn't match, translate it into the listener's required language. 3. If the utterance mixes Hausa and English within a single sentence, treat the full utterance as one unit and translate the entire meaning into the listener's required language -- do not output a half-translated sentence. When translating, apply domain-appropriate resolution rather than word-for-word phrasing: transport fare amounts, route names, and vehicle references (e.g. "Keke" means tricycle/auto-rickshaw, "Okada" means motorcycle taxi -- use the local term with its meaning if no precise English equivalent exists; resolve "dari biyu" in a fare context to "two hundred naira"); clinical symptom descriptions, body-part references, medication names, and duration/severity expressions accurately (e.g. "jikina yana zafi" -> "my body aches," not literally "my body is hot/painful"; preserve dosage instructions exactly -- do not paraphrase quantities, frequencies, or medication names); academic and administrative terminology (resolve course registration terms, department names, exam-related language, NYSC documentation terminology, and fee payment instructions into their standard English or Hausa equivalents as appropriate); general social formulas (handle greetings, blessings, and culturally embedded social formulas appropriately -- e.g. elaborate Hausa greeting protocols should be translated with their social intent preserved, not reduced to a single "hello"; relay formulaic responses like "Alhamdulillahi" unchanged when both parties understand them). Use the conversation so far to resolve references -- a fare, a symptom, a course name, or a document mentioned in earlier turns -- and to keep each speaker's tone and register (formal with a lecturer, casual with a peer, clinical with a chemist) consistent across turns. If a speaker's intent is genuinely ambiguous or unclear, translate your best interpretation of what was said rather than guessing at unstated meaning. Output only the translated or relayed result, nothing else -- no commentary, no explanations, no metadata.
          """.trimIndent(),
      ),
  )

/**
 * TRD §2.1b speaker tag — prefixes each turn so the model knows who is speaking, rather than
 * inferring it from content alone. Neither prompt above names a fixed pair of speaker labels
 * (Campus Mode deliberately leaves the other-party role open-ended: driver, chemist, lecturer,
 * staff, or fellow student), so the tag is derived from ActiveChannel + Environment instead of a
 * literal role guess. `trader` is Market-specific because that prompt already names the role
 * explicitly; `other_party` is the Campus equivalent precisely because no single role name would
 * be correct on every turn. Lowercase snake_case (not e.g. "Trader:") so it reads as a structural
 * metadata token rather than a piece of dialogue the model might mistake for content to translate.
 */
private fun speakerTagFor(environment: Environment, activeChannel: ActiveChannel): String =
  when (activeChannel) {
    // Was "app_user", which named a role no prompt ever defines: the model was handed an entity it
    // had never been told about and had to infer that it meant the customer/student. These names are
    // taken from the prompt text itself so the tag resolves against something the model was actually
    // given.
    ActiveChannel.EARPOD ->
      when (environment) {
        Environment.MARKET -> "customer"
        Environment.CAMPUS -> "student"
      }
    ActiveChannel.PHONE_MIC ->
      when (environment) {
        Environment.MARKET -> "trader"
        Environment.CAMPUS -> "other_party"
      }
  }

/**
 * TRD §2.1 "Baseline Provider" — the stock, pre-quantized `litert-community/gemma-4-E2B-it`
 * artifact via the real LiteRT-LM Kotlin API (com.google.ai.edge.litertlm, verified against the
 * 0.14.0 AAR directly with javap — see class-by-class signatures this file relies on below).
 * Integrated before the fine-tuned adapter exists, so latency/RAM/audio-I/O and multi-turn memory
 * are validated on real inference now, per TRD's baseline-first decision.
 *
 * ⚠ Model-choice open question (unchanged, still real): whether this specific Gemma 4 E2B
 * .litertlm accepts audio input is unconfirmed from the model card alone — the LIBRARY definitely
 * supports it (Content.AudioBytes is a real, generic API, confirmed via javap), but whether the
 * model's own weights were exported with an audio pathway is a property of the model file, not the
 * library. A separate real test (TRD §3.3) fed E2B a genuine overlapping-speech recording and got
 * transcription-like output (degenerate on overlap, but present) — real evidence the pathway is at
 * least partially functional, though not a confirmation for the general single-speaker case this
 * provider needs. translate() below sends audio best-effort; if the model can't use it, the call
 * either ignores it or throws — the latter is already caught by the JS caller (index.tsx's
 * performTranslate), so this fails safe rather than crashing. Confirm empirically once on-device.
 *
 * Engine lifecycle: initialize() can take up to ~10s (LiteRT-LM's own documented guidance) — this
 * runs inside startConversation(), which the translate module already calls from a Coroutine
 * AsyncFunction (off the main thread), so the UI thread is never blocked. The Engine itself is
 * shared across conversations/sessions (created once, lazily, on first use) since re-loading a
 * ~2.6GB model per conversation would be its own latency disaster.
 */
class Gemma4BaselineProvider(
  private val context: Context,
  private val modelPath: String,
  /**
   * Which tier this instance is serving. The fine-tuned artifact (TRD §2.4) is a LoRA-merged export
   * of this same base, loaded through this identical code path with only a different file — so it
   * gets this class rather than a parallel implementation that could drift out of sync with it.
   */
  override val tier: ProviderTier = ProviderTier.BASELINE,
) : TranslationProvider {

  @Volatile private var engine: Engine? = null
  private val engineLock = Any()

  private class BaselineSession(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
    val conversation: Conversation,
  ) : ConversationSession(environment, appUserLanguage, maxWindowTurns)

  /** True when the GPU delegate actually took; surfaced in latencyBreakdownMs so the backend that
   * produced a given timing is never a guess. */
  @Volatile private var usingGpu = false

  /**
   * Whether this .litertlm actually contains an audio tower.
   *
   * Not every export has one. A model built for text only fails at `createConversation` with
   * `NOT_FOUND: TF_LITE_AUDIO_ENCODER_HW not found in the model` as soon as EngineConfig names an
   * audioBackend — the engine loads fine, then the first conversation dies. When that happens the
   * engine is rebuilt without the audio backend and this flips false, so the model stays usable for
   * text instead of the whole tier being lost. [translate] then refuses audio explicitly rather than
   * letting it fail as an opaque JNI error mid-utterance.
   */
  @Volatile private var supportsAudio = true

  @OptIn(ExperimentalApi::class)
  private fun ensureEngine(): Engine =
    engine ?: synchronized(engineLock) {
      engine ?: run {
        // Turns "it feels slow" into numbers. Populates Conversation.benchmarkInfo (prefill/decode
        // token counts + tokens-per-second + time-to-first-token) which translate() folds into
        // latencyBreakdownMs. Counter bookkeeping only — it does not itself slow inference, and
        // it's the only way to tell a prefill-bound turn (long system prompt / audio tokens) from
        // a decode-bound one (long output), which need opposite fixes.
        ExperimentalFlags.enableBenchmark = true

        // CPU first (NFR-07's floor), GPU only behind [PREFER_GPU]. The GPU path is kept because
        // the reasoning for it still holds — prefill is the dominant first-turn cost and is exactly
        // the parallel work a delegate helps — but it is NOT the default: see PREFER_GPU for what
        // trying it by default actually did on-device.
        // Last resort drops the token bound rather than the engine: MAX_NUM_TOKENS is a tuning
        // value, and no tuning value is worth losing offline translation over if this runtime
        // rejects it (it validates the bound against the model — see the "set max_num_tokens to at
        // most" diagnostic in liblitertlm_jni).
        (if (PREFER_GPU) buildEngine(useGpu = true) else null)
          ?: buildEngine(useGpu = false)
          ?: buildEngine(useGpu = false, maxTokens = null)
          ?: error("LiteRT-LM engine failed to initialize")
      }
    }

  /**
   * Builds and initializes one engine, or returns null if that backend is unusable on this device.
   * The audio encoder stays on CPU in both cases: GPU delegation of the audio pathway is the least
   * exercised part of this runtime, and it is not worth risking the whole engine to accelerate the
   * smaller half of the work.
   *
   * @param maxTokens context bound, or null to accept the runtime's own default (see MAX_NUM_TOKENS)
   */
  private fun buildEngine(
    useGpu: Boolean,
    maxTokens: Int? = MAX_NUM_TOKENS,
    withAudio: Boolean = supportsAudio,
  ): Engine? =
    try {
      Engine(
          EngineConfig(
            modelPath = modelPath,
            // Bounds the KV cache instead of leaving it to the runtime default. Left unset, the
            // cache is sized for a context this app never uses — every session is one ~370-454
            // token system prompt plus short utterances, not a long document.
            maxNumTokens = maxTokens,
            // Thread count is set explicitly rather than left to the default: big.LITTLE phones
            // stall LLM decode when work spreads onto the little cores, because every token waits
            // on the slowest thread. Half the reported cores approximates the performance cluster
            // on the usual 4+4 / 1+3+4 layouts. ⚠ Heuristic — verify against the benchmark numbers
            // on a real device before treating it as tuned.
            backend = if (useGpu) Backend.GPU() else Backend.CPU(threadCount = cpuThreadCount()),
            // Naming an audioBackend for a model with no audio tower is what produces the
            // TF_LITE_AUDIO_ENCODER_HW failure — see [supportsAudio].
            audioBackend = if (withAudio) Backend.CPU(threadCount = cpuThreadCount()) else null,
            cacheDir = context.cacheDir.path, // improves load time per LiteRT-LM's own guidance
          ),
        )
        .also {
          it.initialize() // up to ~10s — safe here, caller already runs off the main thread
          usingGpu = useGpu
          engine = it
          Log.i(
            TAG,
            "LiteRT-LM engine initialized on ${if (useGpu) "GPU" else "CPU"}, " +
              "maxNumTokens=${maxTokens ?: "runtime default"}",
          )
        }
    } catch (t: Throwable) {
      Log.w(TAG, "LiteRT-LM ${if (useGpu) "GPU" else "CPU"} backend unavailable: ${t.message}")
      null
    }

  /**
   * A text-only export cannot be detected up front: `Engine.initialize()` succeeds and the failure
   * only surfaces here, on the first `createConversation`, because that is when the audio tower is
   * resolved. So the capability is discovered by trying, and a model missing it is downgraded to
   * text rather than being lost entirely.
   */
  override fun startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession =
    try {
      openConversation(environment, appUserLanguage, maxWindowTurns)
    } catch (t: Throwable) {
      if (!supportsAudio || !isMissingAudioEncoder(t)) throw t
      Log.w(TAG, "Model has no audio tower — rebuilding engine for text-only use: ${t.message}")
      supportsAudio = false
      synchronized(engineLock) {
        runCatching { engine?.close() }
        engine = null
      }
      openConversation(environment, appUserLanguage, maxWindowTurns)
    }

  private fun openConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession {
    val eng = ensureEngine()

    // Greedy decoding (topK = 1) rather than the previous topK=40/topP=0.95/temp=0.3 sampling. Two
    // reasons, both wins here: it drops the per-token top-k sort + nucleus filter over the candidate
    // set, and translation genuinely wants the single most likely token — sampling buys variety that
    // a translator has no use for, and makes output non-reproducible between demo runs.
    // topP/temperature are left neutral rather than zeroed: with exactly one candidate the choice is
    // argmax regardless, so this avoids depending on how the sampler handles a 0.0 temperature
    // divisor.
    val sampler = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0, seed = 0)

    // The fine-tuned artifact may not need the system prompt at all: if the task was trained into
    // the weights, re-sending ~370-454 tokens of instructions every session is pure prefill cost for
    // behaviour the model already has — the single biggest latency lever left. But it is ONLY correct
    // if the fine-tune was trained without these prompts in its inputs; dropping them from a model
    // trained WITH them changes the input distribution it saw and degrades output. So this is a
    // deliberate flag answered by whoever trained it, not something to infer here.
    val sendSystemPrompt = tier != ProviderTier.FINE_TUNED || FINE_TUNED_EXPECTS_SYSTEM_PROMPT

    // The fine-tune gets the exact text it was trained on; the untuned baseline gets the condensed
    // rewrite, where trading tokens for latency costs nothing learned.
    val prompts =
      if (tier == ProviderTier.FINE_TUNED) SYSTEM_PROMPTS_CANONICAL else SYSTEM_PROMPTS

    val conversation =
      eng.createConversation(
        if (sendSystemPrompt) {
          ConversationConfig(
            systemInstruction =
              Contents.of(prompts.getValue(environment).getValue(appUserLanguage)),
            samplerConfig = sampler,
          )
        } else {
          ConversationConfig(samplerConfig = sampler)
        },
      )
    return BaselineSession(environment, appUserLanguage, maxWindowTurns, conversation)
  }

  /** Releases the native Engine and its multi-GB allocation. Must be called before dropping a
   * reference to this provider — two resident engines is >5GB of weights, which the low-memory
   * killer resolves on the target hardware by killing the app. */
  fun close() {
    synchronized(engineLock) {
      runCatching { engine?.close() }
      engine = null
    }
  }

  override suspend fun translate(
    session: ConversationSession,
    audioPcm16kMono: ShortArray?,
    textOverride: String?,
    activeChannel: ActiveChannel,
  ): TranslationResultRecord {
    val baseline = session as BaselineSession
    val startedAt = System.currentTimeMillis()

    // TRD §2.1b: prefix with who's speaking rather than a target-language hint — the system
    // prompt's own rules 1-3 already fully specify how to derive the required output language
    // from the speaker tag + which role needs what, so a separate hint here is redundant.
    // Baseline-only. Every one of the 1048 fine-tuning records presents the utterance bare, with no
    // speaker prefix, so prefixing one for the fine-tuned model is a distribution shift on its most
    // sensitive input. It keeps its bearings from the prompt's role definitions plus the language
    // actually spoken, which is exactly what it was trained to do.
    val prefix =
      if (tier == ProviderTier.FINE_TUNED) {
        ""
      } else {
        "${speakerTagFor(baseline.environment, activeChannel)}: "
      }

    val contents =
      when {
        textOverride != null -> Contents.of(Content.Text("$prefix$textOverride"))
        audioPcm16kMono != null -> {
          if (!supportsAudio) {
            throw IllegalStateException(
              "This model was exported without an audio encoder, so speech input is unavailable. " +
                "Type what was said instead, or use the baseline model.",
            )
          }
          val wav = Content.AudioBytes(pcm16ToWavBytes(audioPcm16kMono))
          if (prefix.isEmpty()) Contents.of(wav) else Contents.of(Content.Text(prefix.trimEnd()), wav)
        }
        else -> throw IllegalArgumentException("translate() requires audioPcm16kMono or textOverride")
      }

    // Synchronous send (TRD §2.2 sendMessage, not the Flow-based sendMessageAsync): the JS bridge
    // contract today returns one final TranslationResult per call, not a live stream, so the
    // simpler blocking API is the correct match — this coroutine is already off the main thread.
    val response = baseline.conversation.sendMessage(contents)
    val translatedText =
      stripReasoning(
        response.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
      )

    return TranslationResultRecord(
      translatedText = translatedText,
      translatedAudio = null,
      sourceTranscript = textOverride,
      latencyBreakdownMs = buildLatencyBreakdown(baseline, System.currentTimeMillis() - startedAt),
    )
  }

  /**
   * Wall-clock total plus, when the benchmark flag took effect, the split that actually explains it.
   * The two diagnoses need opposite fixes: a high prefillTokens with low prefillTokensPerSec means
   * the input side dominates (the ~700-token system prompt on turn 1, or audio tokens every turn —
   * shorten the prompt / shorten the utterance), whereas a high decodeTokens means the model is
   * simply writing too much (constrain the output). Guarded because benchmarkInfo is only meaningful
   * once ExperimentalFlags.enableBenchmark has been set, and it's diagnostics — never worth throwing
   * away a good translation over.
   */
  @OptIn(ExperimentalApi::class)
  private fun buildLatencyBreakdown(session: BaselineSession, totalMs: Long): Map<String, Long> =
    buildMap {
      put("total", totalMs)
      put("gpu", if (usingGpu) 1L else 0L)
      // Declared as a function, not a `val`, so property-access syntax doesn't apply here.
      runCatching { session.conversation.getBenchmarkInfo() }.getOrNull()?.let { b ->
        put("timeToFirstTokenMs", (b.timeToFirstTokenInSecond * 1000).toLong())
        put("prefillTokens", b.lastPrefillTokenCount.toLong())
        put("decodeTokens", b.lastDecodeTokenCount.toLong())
        put("prefillTokensPerSec", b.lastPrefillTokensPerSecond.toLong())
        put("decodeTokensPerSec", b.lastDecodeTokensPerSecond.toLong())
      }
    }

  override fun endConversation(session: ConversationSession) {
    (session as BaselineSession).conversation.close()
  }
}

private const val TAG = "ZaurelinkGemma"

private val THINK_BLOCK =
  Regex("<(think|thought)>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
private val THINK_OPEN = Regex("<(think|thought)>", RegexOption.IGNORE_CASE)

/**
 * Removes Gemma reasoning blocks before the text is shown or spoken.
 *
 * Without this, a model that reasons before answering has its entire chain of thought rendered into
 * the transcript AND read aloud by TTS — in a market, over a loudspeaker, in place of the
 * translation. The prompt already says to output only the result, but a prompt is a request, not a
 * guarantee, and this is the one place where ignoring it is loudly visible to a bystander.
 *
 * The unterminated case is handled deliberately: if generation stops mid-thought there is no closing
 * tag, so everything from the opening tag on is reasoning and is dropped. Returning the partial
 * thought would be worse than returning nothing.
 */
private fun stripReasoning(raw: String): String {
  val closed = THINK_BLOCK.replace(raw, "")
  val dangling = THINK_OPEN.find(closed)
  return (if (dangling != null) closed.substring(0, dangling.range.first) else closed).trim()
}

/** Matches the LiteRT-LM failure a text-only export produces once an audioBackend is configured. */
private fun isMissingAudioEncoder(t: Throwable): Boolean =
  generateSequence(t) { it.cause }.any {
    it.message?.contains("AUDIO_ENCODER", ignoreCase = true) == true
  }

/**
 * Whether to attempt the GPU delegate before falling back to CPU.
 *
 * OFF because of a measured on-device regression, not caution in the abstract: with GPU attempted
 * first, `Engine.initialize()` sat for several minutes on the "Preparing offline model" state
 * instead of the ~10s the CPU path takes, because the delegate has to compile kernels and stage the
 * whole int4 weight set into GPU memory before it returns. Two things make that unrecoverable
 * rather than merely slow: `initialize()` is a blocking native call, so no timeout can abandon it,
 * and the CPU fallback below only runs once it has finally failed — a device short on GPU memory
 * pays the full stall AND then still loads on CPU.
 *
 * Flip to true only to measure it on a specific device, and read the `gpu` field in
 * latencyBreakdownMs to confirm the delegate actually took rather than silently falling back.
 */
private const val PREFER_GPU = false

/**
 * Context bound passed as `EngineConfig.maxNumTokens`, which is what sizes the KV cache (the runtime
 * derives `kv_cache_max` from it). Unset previously, so the cache was sized for the runtime default
 * rather than for this app's actual shape: one ~370-454 token system prompt plus short utterances.
 *
 * 4096 rather than something tighter, and the reason is [ConversationSession.maxWindowTurns] — the
 * declared 8-turn window is enforced by the Mock provider but NOT here, because LiteRT-LM's
 * `Conversation` exposes no history-eviction API (verified against the 0.14.0 AAR: sendMessage,
 * cancelProcess, getTokenCount, close — nothing that trims). So history on this path grows for a
 * whole session until the inactivity reset recycles it, and the bound has to cover that session, not
 * 8 turns. Truncating mid-conversation would corrupt translations silently, which is far worse than
 * holding a larger cache.
 *
 * Implementing the real window means recycling the Conversation against `getTokenCount()` and
 * replaying the last N turns through `ConversationConfig.initialMessages` — deliberately deferred,
 * since it changes what the model sees mid-session.
 */
private const val MAX_NUM_TOKENS = 4096

/**
 * Whether the fine-tuned artifact expects a system prompt in its input.
 *
 * TRUE, and this is now settled by the training data rather than assumed: every one of the 1048
 * records in `zaurelink_training_data.jsonl` carries a system turn (1048 system / 1308 user / 1308
 * model), and their system-prompt lengths are exactly the two canonical sizes, 2154 and 2829 chars.
 * The model has never seen an input without one.
 *
 * So dropping the prompt is NOT an available latency win, despite being the largest one on paper:
 * it would put the model outside its training distribution on every single turn. Leave this true.
 * The prompt cost is reduced instead by the condensed [SYSTEM_PROMPTS] on the untuned baseline,
 * where no training expectation exists to violate.
 */
private const val FINE_TUNED_EXPECTS_SYSTEM_PROMPT = true

/** Threads for the CPU backend — see the rationale at the Backend.CPU call site. Clamped low: on a
 * big.LITTLE phone, oversubscribing past the performance cluster makes each token wait on a little
 * core, so more threads can be strictly slower. */
private fun cpuThreadCount(): Int =
  (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 4)

/** Wraps raw PCM16 mono samples in a standard 44-byte RIFF/WAVE header — Content.AudioBytes takes
 * a self-describing audio byte stream, not headerless PCM. */
private fun pcm16ToWavBytes(pcm: ShortArray, sampleRate: Int = 16000): ByteArray {
  val dataSize = pcm.size * 2
  val out = ByteArrayOutputStream(44 + dataSize)

  fun writeString(s: String) = s.forEach { out.write(it.code) }
  fun writeIntLE(v: Int) {
    out.write(v and 0xff)
    out.write((v shr 8) and 0xff)
    out.write((v shr 16) and 0xff)
    out.write((v shr 24) and 0xff)
  }
  fun writeShortLE(v: Int) {
    out.write(v and 0xff)
    out.write((v shr 8) and 0xff)
  }

  writeString("RIFF")
  writeIntLE(36 + dataSize)
  writeString("WAVE")
  writeString("fmt ")
  writeIntLE(16) // PCM fmt chunk size
  writeShortLE(1) // audio format = PCM
  writeShortLE(1) // mono
  writeIntLE(sampleRate)
  writeIntLE(sampleRate * 2) // byte rate = sampleRate * blockAlign
  writeShortLE(2) // block align = channels * bitsPerSample/8
  writeShortLE(16) // bits per sample
  writeString("data")
  writeIntLE(dataSize)
  for (s in pcm) writeShortLE(s.toInt())

  return out.toByteArray()
}
