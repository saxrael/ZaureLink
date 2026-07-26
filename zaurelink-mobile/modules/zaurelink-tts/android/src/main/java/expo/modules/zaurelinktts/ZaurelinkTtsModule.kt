package expo.modules.zaurelinktts

import android.content.Context
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * TRD §5 audio-output bridge. Routes by language: English (and any system Hausa voice) through the
 * device TextToSpeech; Hausa through the Meta MMS ONNX voice ([MmsHausaTtsEngine]) once its model
 * is downloaded. A failed/unavailable voice resolves speak() to false so the UI degrades to text
 * (NFR-06 / FR-06), never crashing. Gemma still does all translation — this only vocalizes it.
 */
class ZaurelinkTtsModule : Module() {
  private var systemEngine: SystemTtsEngine? = null
  private var mmsEngine: MmsHausaTtsEngine? = null

  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  override fun definition() =
    ModuleDefinition {
      Name("ZaurelinkTts")

      Events("onTtsReady", "onTtsStart", "onTtsDone", "onTtsError")

      // TextToSpeech init is async; resolve once the engine reports ready.
      AsyncFunction("initialize") { promise: Promise ->
        val existing = systemEngine
        if (existing != null) {
          promise.resolve(existing.isReady())
        } else {
          systemEngine =
            SystemTtsEngine(
              context = context,
              onReady = { ok ->
                sendEvent("onTtsReady", mapOf("ready" to ok))
                promise.resolve(ok)
              },
              onEvent = { name, body -> sendEvent(name, body) },
            )
        }
      }

      // Point the Hausa voice at a downloaded MMS model (facebook/mms-tts-hau). Returns true if it
      // loaded. Called by JS once the voice-model download completes + verifies.
      Function("setHausaVoiceModelPath") { path: String ->
        mmsEngine?.release()
        mmsEngine =
          try {
            MmsHausaTtsEngine(context, path.removePrefix("file://")) { name, body ->
              sendEvent(name, body)
            }
          } catch (t: Throwable) {
            null
          }
        mmsEngine != null
      }

      Function("isLanguageAvailable") { language: TtsLanguage ->
        when (language) {
          TtsLanguage.HAUSA ->
            mmsEngine != null || systemEngine?.isLanguageAvailable(TtsLanguage.HAUSA) == true
          TtsLanguage.ENGLISH -> systemEngine?.isLanguageAvailable(TtsLanguage.ENGLISH) == true
        }
      }

      /**
       * @param toLoudspeaker true when this translation is FOR THE OTHER PARTY, so it must come out
       *   of the phone's speaker rather than following media routing into the app user's earpiece.
       *   Without it the private and public sides of the conversation collapse onto one channel and
       *   the person being translated for hears nothing.
       */
      AsyncFunction("speak") { text: String, language: TtsLanguage, toLoudspeaker: Boolean ->
        when (language) {
          // Hausa → MMS voice if loaded, else whatever the system offers (usually nothing → text).
          // The elvis operator cannot express this: MmsHausaTtsEngine.speak returns a non-null
          // Boolean, so `mms?.speak(t) ?: system…` only reached the fallback when the ENGINE was
          // absent — never when a loaded engine failed to produce speakable tokens. That is the
          // common case (text that reduces to nothing in the vocab), and it silently swallowed the
          // utterance instead of falling back.
          TtsLanguage.HAUSA -> {
            val spoken = mmsEngine?.speak(text, toLoudspeaker) == true
            spoken || systemEngine?.speak(text, TtsLanguage.HAUSA, toLoudspeaker) == true
          }
          TtsLanguage.ENGLISH ->
            systemEngine?.speak(text, TtsLanguage.ENGLISH, toLoudspeaker) == true
        }
      }

      Function("stop") {
        systemEngine?.stop()
        mmsEngine?.stop()
      }

      OnDestroy {
        systemEngine?.release()
        systemEngine = null
        mmsEngine?.release()
        mmsEngine = null
      }
    }
}
