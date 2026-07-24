package expo.modules.zaurelinktranslate

import expo.modules.kotlin.records.Field
import expo.modules.kotlin.records.Record
import expo.modules.kotlin.types.Enumerable

// TRD §2.1: Environment determines domain vocabulary (2 total). Environment × AppUserLanguage
// together select exactly 1 of 4 system prompts (§2.1a).
enum class Environment(val value: String) : Enumerable {
  MARKET("market"),
  CAMPUS("campus"),
}

/**
 * TRD §2.1 correction: the old `Direction` enum conflated three separate concepts into one —
 * which physical channel is active, what language the app user requires, and what language was
 * actually spoken on a given turn. It silently assumed every app user is English-dominant, which
 * is wrong (PRD §2 — the app user's language is a genuine per-session choice, either direction).
 * `Direction` is retired, replaced by [AppUserLanguage] (session-level, this file) and
 * [ActiveChannel] (per-turn routing, this file). Actual spoken language is detected by the model
 * itself per turn and is never passed in as a parameter.
 */
enum class AppUserLanguage(val value: String) : Enumerable {
  HAUSA("hausa"),
  ENGLISH("english"),
}

/** Purely mechanical: which mic captured this turn. Carries NO language information — never
 * assume EARPOD means English or PHONE_MIC means Hausa (TRD §2.1). */
enum class ActiveChannel(val value: String) : Enumerable {
  EARPOD("earpod"), // app user speaking (their private channel)
  PHONE_MIC("phone_mic"), // other party speaking (the public channel)
}

/** Derives which language a turn's output must be in — never stored, always computed (TRD §2.1's
 * translate() doc): EARPOD → the app user is speaking, so the *other party* is listening and needs
 * the opposite of what the app user requires. PHONE_MIC → the other party is speaking, so the *app
 * user* is listening and needs their own selected language. */
fun requiredOutputLanguage(appUserLanguage: AppUserLanguage, activeChannel: ActiveChannel): AppUserLanguage =
  when (activeChannel) {
    ActiveChannel.EARPOD ->
      if (appUserLanguage == AppUserLanguage.HAUSA) AppUserLanguage.ENGLISH else AppUserLanguage.HAUSA
    ActiveChannel.PHONE_MIC -> appUserLanguage
  }

// TRD §2.1 three-tier provider strategy. Switchable by config flag / runtime dev toggle.
enum class ProviderTier(val value: String) : Enumerable {
  MOCK("mock"),
  BASELINE("baseline"),
  FINE_TUNED("fine_tuned"),
}

/** Bridge-serializable translation result (TRD §2.2 TranslationResult). translatedText is always
 * non-null; translatedAudio is optional and the UI must degrade gracefully when it is null. */
class TranslationResultRecord(
  @Field val translatedText: String,
  @Field val translatedAudio: List<Int>?,
  @Field val sourceTranscript: String?,
  @Field val latencyBreakdownMs: Map<String, Long>,
) : Record

/** Holds bounded turn history and the app user's selected language for one active conversation
 * (TRD §2.2). Providers subclass this to attach their own per-session state (mock: a turn list;
 * baseline: a LiteRT-LM native session). */
abstract class ConversationSession(
  val environment: Environment,
  val appUserLanguage: AppUserLanguage,
  val maxWindowTurns: Int,
)

/**
 * TRD §2.2 TranslationProvider — the single swap boundary between "app that works" and "app that
 * depends on the fine-tuned model." All three tiers implement this identical contract, so changing
 * tiers is a config value, not app/UI code (TRD §2.1). translate() takes ShortArray? PCM directly,
 * exactly as the TRD specifies — the raw PCM lives entirely in native/Kotlin space and never
 * crosses the JS bridge (the module resolves an utterance id to this ShortArray before calling).
 */
interface TranslationProvider {
  val tier: ProviderTier

  /** Starts a new bounded-memory conversation for the given environment and the app user's
   * selected language. Called on Mode or Language selection/switch and on session end + restart
   * (PRD §3.5 — changing either ends the current session and starts a new one). */
  fun startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession

  /**
   * @param activeChannel purely mechanical routing (TRD §2.1) — the required output language for
   *   this turn is derived via [requiredOutputLanguage], never passed in directly. The model (or,
   *   for Mock, a text heuristic) detects the speaker's actual language and only translates if it
   *   doesn't already match the required language — if it already matches (code-switching), the
   *   result is relayed unchanged (FR-13).
   */
  suspend fun translate(
    session: ConversationSession,
    audioPcm16kMono: ShortArray?,
    textOverride: String?,
    activeChannel: ActiveChannel,
  ): TranslationResultRecord

  fun endConversation(session: ConversationSession)
}
