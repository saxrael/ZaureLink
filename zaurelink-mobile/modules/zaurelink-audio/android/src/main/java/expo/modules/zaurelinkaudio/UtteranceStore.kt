package expo.modules.zaurelinkaudio

/**
 * Native hand-off point between zaurelink-audio and zaurelink-translate (TRD §2.2/§4.2): JS only
 * ever sees an opaque utteranceId (via onUtteranceReady); the translate module resolves that id
 * to the retained PCM directly through this store, so raw audio never crosses the JS bridge.
 *
 * A small bounded map, not a single "last" slot: capture and translation run concurrently enough
 * (an utterance could finish while a previous translate() call is still in flight) that a single
 * slot risks the wrong PCM being consumed. Entries are one-shot — take() removes what it returns —
 * so memory never accumulates across a long session.
 */
object UtteranceStore {
  private const val MAX_ENTRIES = 4
  private val entries = LinkedHashMap<String, ShortArray>()

  @Synchronized
  fun put(utteranceId: String, pcm: ShortArray) {
    entries[utteranceId] = pcm
    while (entries.size > MAX_ENTRIES) {
      val oldest = entries.keys.firstOrNull() ?: break
      entries.remove(oldest)
    }
  }

  @Synchronized
  fun take(utteranceId: String): ShortArray? = entries.remove(utteranceId)
}
