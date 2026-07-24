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
            MmsHausaTtsEngine(path.removePrefix("file://")) { name, body -> sendEvent(name, body) }
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

      AsyncFunction("speak") { text: String, language: TtsLanguage ->
        when (language) {
          // Hausa → MMS voice if loaded, else whatever the system offers (usually nothing → text).
          TtsLanguage.HAUSA ->
            mmsEngine?.speak(text) ?: (systemEngine?.speak(text, TtsLanguage.HAUSA) ?: false)
          TtsLanguage.ENGLISH -> systemEngine?.speak(text, TtsLanguage.ENGLISH) ?: false
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
