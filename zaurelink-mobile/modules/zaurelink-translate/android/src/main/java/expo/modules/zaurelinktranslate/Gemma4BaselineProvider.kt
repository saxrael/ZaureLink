package expo.modules.zaurelinktranslate

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.ByteArrayOutputStream

// TRD §2.1a (revised): 4 system prompts (Environment × AppUserLanguage), verbatim — not 2. None
// vary per-turn; ModeConfigResolver selects exactly one at session start. Each prompt applies 3
// ordered rules (pass-through / translate / treat a mixed-language utterance as one unit) and
// gives concrete domain glossaries (currency, units, idiom, greetings for Market; fares, clinical
// language, academic/admin terms for Campus) rather than trusting the model to infer local
// convention unaided.
private val SYSTEM_PROMPTS: Map<Environment, Map<AppUserLanguage, String>> =
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
    ActiveChannel.EARPOD -> "app_user"
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
) : TranslationProvider {
  override val tier = ProviderTier.BASELINE

  @Volatile private var engine: Engine? = null
  private val engineLock = Any()

  private class BaselineSession(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
    val conversation: Conversation,
  ) : ConversationSession(environment, appUserLanguage, maxWindowTurns)

  private fun ensureEngine(): Engine =
    engine ?: synchronized(engineLock) {
      engine ?: Engine(
          EngineConfig(
            modelPath = modelPath,
            backend = Backend.CPU(), // NFR-07: CPU/XNNPACK, not GPU-assisted, per the target floor
            audioBackend = Backend.CPU(),
            cacheDir = context.cacheDir.path, // improves load time per LiteRT-LM's own guidance
          ),
        )
        .also {
          it.initialize() // up to ~10s — safe here, caller already runs off the main thread
          engine = it
        }
    }

  override fun startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession {
    val eng = ensureEngine()
    val conversation =
      eng.createConversation(
        ConversationConfig(
          systemInstruction = Contents.of(SYSTEM_PROMPTS.getValue(environment).getValue(appUserLanguage)),
          // Low temperature: translation wants faithful, low-variance output, not creative sampling.
          samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 0),
        ),
      )
    return BaselineSession(environment, appUserLanguage, maxWindowTurns, conversation)
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
    val tag = speakerTagFor(baseline.environment, activeChannel)

    val contents =
      when {
        textOverride != null -> Contents.of(Content.Text("$tag: $textOverride"))
        audioPcm16kMono != null ->
          Contents.of(Content.Text("$tag:"), Content.AudioBytes(pcm16ToWavBytes(audioPcm16kMono)))
        else -> throw IllegalArgumentException("translate() requires audioPcm16kMono or textOverride")
      }

    // Synchronous send (TRD §2.2 sendMessage, not the Flow-based sendMessageAsync): the JS bridge
    // contract today returns one final TranslationResult per call, not a live stream, so the
    // simpler blocking API is the correct match — this coroutine is already off the main thread.
    val response = baseline.conversation.sendMessage(contents)
    val translatedText =
      response.contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString("") { it.text }
        .trim()

    return TranslationResultRecord(
      translatedText = translatedText,
      translatedAudio = null,
      sourceTranscript = textOverride,
      latencyBreakdownMs = mapOf("baselineProvider" to (System.currentTimeMillis() - startedAt)),
    )
  }

  override fun endConversation(session: ConversationSession) {
    (session as BaselineSession).conversation.close()
  }
}

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
