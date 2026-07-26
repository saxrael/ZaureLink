package expo.modules.zaurelinktts

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import expo.modules.kotlin.types.Enumerable
import java.util.Locale

// TRD §5.1 TtsLanguage.
enum class TtsLanguage(val value: String) : Enumerable {
  ENGLISH("english"),
  HAUSA("hausa"),
}

/**
 * TRD §5 audio-output engine. Interface-backed so the Day-1 baseline and the guaranteed-Hausa
 * eSpeak NG engine are interchangeable — same swap philosophy as the VAD/provider tiers.
 *
 * Note vs TRD §5.1: the spec's synthesize(text, language): ShortArray? returns PCM so the routing
 * controller can play it on the correct channel. The system-TTS baseline plays directly instead
 * (speak()), which is simpler and dependency-free; the eSpeak NG slot can return PCM if per-channel
 * routing of TTS output is needed. Either way, a failure returns false so the UI degrades to
 * text-only and never crashes/blocks (NFR-06 / FR-06 — text is the failsafe, audio is the target).
 */
interface TtsOutputEngine {
  fun isReady(): Boolean

  fun isLanguageAvailable(language: TtsLanguage): Boolean

  /** True if the eSpeak NG TTS engine app is installed as a system engine (guarantees Hausa). */
  fun isEspeakInstalled(): Boolean

  /**
   * Returns true if speech started; false if not ready or the language has no installed voice.
   *
   * @param toLoudspeaker route out of the phone's built-in speaker rather than following the system
   *   default. This is what keeps the two sides of the conversation on their own channels: audio for
   *   the other party must not follow media routing into the app user's Bluetooth earpiece.
   */
  fun speak(text: String, language: TtsLanguage, toLoudspeaker: Boolean = false): Boolean

  fun stop()

  fun release()
}

private fun localeFor(language: TtsLanguage): Locale =
  when (language) {
    TtsLanguage.ENGLISH -> Locale.ENGLISH
    TtsLanguage.HAUSA -> Locale.forLanguageTag("ha") // BCP-47; avoids the deprecated Locale(String) ctor
  }

/**
 * TRD §5 audio output via Android's system TextToSpeech — zero dependency, offline, and (crucially)
 * GPL-free: eSpeak NG is GPL v3, so instead of embedding it we route to it as an installed system
 * engine, keeping ZaureLink's own licensing clean.
 *
 * Two engines are held: the device's default (natural English out of the box) and, when the eSpeak
 * NG app is installed, a second instance explicitly targeting the eSpeak engine package for
 * guaranteed Hausa. Hausa prefers eSpeak; English uses the default. If no Hausa voice exists on
 * either, isLanguageAvailable(HAUSA) is false and the UI degrades to on-screen text (NFR-06) while
 * offering a one-tap install of eSpeak NG (see isEspeakInstalled()).
 */
class SystemTtsEngine(
  context: Context,
  private val onReady: (Boolean) -> Unit,
  private val onEvent: (String, Map<String, Any?>) -> Unit,
) : TtsOutputEngine {
  @Volatile private var defaultReady = false
  @Volatile private var espeakReady = false
  @Volatile private var espeakInstalled = false
  private var espeakTts: TextToSpeech? = null
  private var counter = 0L

  private val appContext = context.applicationContext

  private val progressListener =
    object : UtteranceProgressListener() {
      override fun onStart(utteranceId: String?) = onEvent("onTtsStart", mapOf("id" to utteranceId))

      override fun onDone(utteranceId: String?) = onEvent("onTtsDone", mapOf("id" to utteranceId))

      @Deprecated("Deprecated in Java")
      override fun onError(utteranceId: String?) = onEvent("onTtsError", mapOf("id" to utteranceId))
    }

  private val defaultTts: TextToSpeech =
    TextToSpeech(appContext) { status ->
      defaultReady = status == TextToSpeech.SUCCESS
      if (defaultReady) maybeInitEspeak()
      onReady(defaultReady)
    }

  init {
    defaultTts.setOnUtteranceProgressListener(progressListener)
  }

  /** Detect the eSpeak NG engine via the TTS framework (no <queries> needed) and, if present but
   * not already the default, spin up a dedicated instance targeting it for Hausa. */
  private fun maybeInitEspeak() {
    espeakInstalled =
      try {
        defaultTts.engines.any { it.name == ESPEAK_PACKAGE }
      } catch (_: Throwable) {
        false
      }
    if (espeakInstalled && espeakTts == null) {
      espeakTts =
        TextToSpeech(
          appContext,
          { status -> espeakReady = status == TextToSpeech.SUCCESS },
          ESPEAK_PACKAGE,
        )
          .apply { setOnUtteranceProgressListener(progressListener) }
    }
  }

  private fun hasLang(tts: TextToSpeech, language: TtsLanguage): Boolean =
    try {
      tts.isLanguageAvailable(localeFor(language)) >= TextToSpeech.LANG_AVAILABLE
    } catch (_: Throwable) {
      false
    }

  override fun isReady(): Boolean = defaultReady

  override fun isEspeakInstalled(): Boolean = espeakInstalled

  override fun isLanguageAvailable(language: TtsLanguage): Boolean =
    (espeakReady && espeakTts?.let { hasLang(it, language) } == true) ||
      (defaultReady && hasLang(defaultTts, language))

  override fun speak(text: String, language: TtsLanguage, toLoudspeaker: Boolean): Boolean {
    // Hausa prefers the eSpeak engine (guaranteed voice); English (and any Hausa fallback) uses the
    // default engine, keeping natural English while still getting Hausa when eSpeak is present.
    val useEspeak =
      language == TtsLanguage.HAUSA && espeakReady && espeakTts?.let { hasLang(it, language) } == true
    val engine = if (useEspeak) espeakTts else defaultTts
    if (engine == null) return false
    if (!hasLang(engine, language)) return false
    engine.language = localeFor(language)
    val id = "tts-${++counter}"
    // Passing null params defaulted this to STREAM_MUSIC, which follows Bluetooth exactly like the
    // MMS path did. TextToSpeech exposes no per-utterance device selection, so the stream itself is
    // declared as guidance audio, which the platform keeps on the device speaker while a call-style
    // SCO link holds the private channel.
    val params =
      Bundle().apply {
        putInt(
          TextToSpeech.Engine.KEY_PARAM_STREAM,
          if (toLoudspeaker) AudioManager.STREAM_NOTIFICATION else AudioManager.STREAM_MUSIC,
        )
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id)
      }
    return engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, id) == TextToSpeech.SUCCESS
  }

  override fun stop() {
    defaultTts.stop()
    espeakTts?.stop()
  }

  override fun release() {
    defaultTts.stop()
    defaultTts.shutdown()
    espeakTts?.stop()
    espeakTts?.shutdown()
    espeakTts = null
  }

  companion object {
    // eSpeak NG for Android (reecedunn), the same package on Play Store and F-Droid.
    const val ESPEAK_PACKAGE = "com.reecedunn.espeak"
  }
}
