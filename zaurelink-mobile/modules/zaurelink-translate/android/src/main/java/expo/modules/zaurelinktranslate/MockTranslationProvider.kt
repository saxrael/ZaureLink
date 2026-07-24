package expo.modules.zaurelinktranslate

import kotlinx.coroutines.delay

// Canned responses keyed by "environment:outputLanguage" (TRD §2.1 MockTranslationProvider). Real
// market/clinical phrasing stands in for what the fine-tuned model will eventually produce.
private val MOCK_RESPONSES: Map<String, List<String>> =
  mapOf(
    "market:english" to
      listOf(
        "How much for this cloth?",
        "I can't reduce it any further, last price.",
        "Give me two hundred naira change.",
      ),
    "market:hausa" to
      listOf(
        "Nawa ne wannan?",
        "Ba zan iya rangwame ba, wannan shine farashin karshe.",
        "Ka ba ni sauran kudi na naira dari biyu.",
      ),
    "campus:english" to
      listOf(
        "I have been feeling feverish since yesterday.",
        "The Keke fare to the main gate is how much?",
        "My stomach has been hurting since morning.",
      ),
    "campus:hausa" to
      listOf(
        "Ina jin zazzabi tun jiya.",
        "Nawa ne kudin keke zuwa babbar kofa?",
        "Cikina na ciwo tun da safe.",
      ),
  )

// Crude text-only language heuristic, for simulating FR-13 (pass-through on code-switching) in the
// Mock tier. Only usable on typed text — there's no ASR here, so audio utterances can't be
// language-detected and always fall through to "translate" below (an honest Mock limitation, not a
// bug). The real providers detect spoken language via the model itself; the 4 system prompts (TRD
// §2.1a) already instruct Gemma to relay unchanged when it already matches — no code-side logic
// needed there. Ties/unknown default to "needs translation" (the safer default).
private val HAUSA_CHARS = charArrayOf('ƙ', 'Ƙ', 'ɓ', 'Ɓ', 'ɗ', 'Ɗ', 'ā', 'ū')
private val HAUSA_WORDS =
  setOf("ba", "ne", "ce", "kuma", "amma", "don", "yau", "gobe", "nawa", "ina", "kudi", "wannan", "shine", "zan", "iya", "kudin")
private val ENGLISH_WORDS =
  setOf("the", "is", "are", "you", "how", "much", "please", "this", "that", "for", "and", "can", "give", "my")

private fun detectLanguage(text: String): AppUserLanguage? {
  val lower = text.lowercase()
  var hausaScore = lower.count { it in HAUSA_CHARS } * 3
  var englishScore = 0
  for (word in Regex("[a-zà-ÿ']+").findAll(lower).map { it.value }) {
    if (word in HAUSA_WORDS) hausaScore++
    if (word in ENGLISH_WORDS) englishScore++
  }
  return when {
    hausaScore == 0 && englishScore == 0 -> null
    hausaScore > englishScore -> AppUserLanguage.HAUSA
    englishScore > hausaScore -> AppUserLanguage.ENGLISH
    else -> null
  }
}

/**
 * TRD §2.1 Day-1 tier. No model behind it — canned/rule-based responses with a ~200ms artificial
 * delay to simulate real latency, and a simple in-memory bounded turn history. Lets the whole app
 * (UI, routing, session lifecycle, audio hand-off) be built and demoed with zero model dependency.
 */
class MockTranslationProvider : TranslationProvider {
  override val tier = ProviderTier.MOCK

  private class MockSession(environment: Environment, appUserLanguage: AppUserLanguage, maxWindowTurns: Int) :
    ConversationSession(environment, appUserLanguage, maxWindowTurns) {
    val turnHistory = ArrayList<String>()
  }

  override fun startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession = MockSession(environment, appUserLanguage, maxWindowTurns)

  override suspend fun translate(
    session: ConversationSession,
    audioPcm16kMono: ShortArray?,
    textOverride: String?,
    activeChannel: ActiveChannel,
  ): TranslationResultRecord {
    val mock = session as MockSession
    val startedAt = System.currentTimeMillis()
    delay(200)

    val outputLanguage = requiredOutputLanguage(mock.appUserLanguage, activeChannel)
    val key = "${mock.environment.value}:${outputLanguage.value}"
    val pool = MOCK_RESPONSES[key] ?: listOf("(mock) translation unavailable for $key")

    // FR-13 simulation: if the typed text is already in the required output language, relay it
    // unchanged instead of substituting a canned phrase — demonstrates pass-through, not just
    // translation, from the text box (audio can't be language-detected without real ASR).
    val alreadyCorrectLanguage = textOverride != null && detectLanguage(textOverride) == outputLanguage
    val translatedText =
      when {
        alreadyCorrectLanguage -> "(pass-through, already ${outputLanguage.value}) $textOverride"
        textOverride != null -> "(mock echo) $textOverride"
        audioPcm16kMono != null ->
          "${pool[mock.turnHistory.size % pool.size]} (from ${audioPcm16kMono.size} audio samples)"
        else -> pool[mock.turnHistory.size % pool.size]
      }

    mock.turnHistory.add(translatedText)
    if (mock.turnHistory.size > mock.maxWindowTurns) {
      mock.turnHistory.removeAt(0)
    }

    val sourceTranscript = textOverride ?: audioPcm16kMono?.let { "(spoken, ${it.size} samples)" }
    return TranslationResultRecord(
      translatedText = translatedText,
      translatedAudio = null,
      sourceTranscript = sourceTranscript,
      latencyBreakdownMs = mapOf("mockProvider" to (System.currentTimeMillis() - startedAt)),
    )
  }

  override fun endConversation(session: ConversationSession) {
    (session as MockSession).turnHistory.clear()
  }
}
