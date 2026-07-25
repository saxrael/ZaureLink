package expo.modules.zaurelinktranslate

import android.content.Context
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.zaurelinkaudio.UtteranceStore
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Bridge for the TRD §2.1 three-tier provider strategy. Owns session handles and the active tier;
 * delegates all translation to the selected [TranslationProvider]. Switching tiers is a single
 * call (setProvider) with no other app/UI change — the whole point of the swap boundary.
 *
 * Bridge hand-off rule: the JS side passes an opaque utteranceId, never raw PCM. UtteranceStore
 * (owned by zaurelink-audio, reached here via a direct Gradle project dependency) resolves that id
 * to the retained native ShortArray, which is handed to the provider exactly as TRD §2.2 specifies.
 * Only ids and text cross the JS bridge.
 */
class ZaurelinkTranslateModule : Module() {
  private val context: Context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private val mockProvider by lazy { MockTranslationProvider() }

  // Not `by lazy` like the mock: the Gemma providers need a JS-supplied modelPath, and are rebuilt
  // if that path or the tier changes (e.g. switching between the baseline and fine-tuned artifacts).
  // Keyed by tier so both can be resident: the baseline stays loadable as the TRD §2.4 rollback
  // rather than being destroyed the moment the fine-tuned model is selected.
  private val gemmaProviders = mutableMapOf<ProviderTier, Gemma4BaselineProvider>()
  private val gemmaModelPaths = mutableMapOf<ProviderTier, String>()

  @Volatile
  private var tier: ProviderTier = ProviderTier.MOCK

  // sessionId -> (provider that created it, its session). Binding the provider to the session keeps
  // an in-flight conversation consistent even if the active tier is toggled.
  private val sessions = mutableMapOf<String, Pair<TranslationProvider, ConversationSession>>()

  /**
   * Records the model path for a Gemma tier and returns that tier for activation. Both Gemma tiers
   * are the same LiteRT-LM integration over a different .litertlm file, so they share one
   * construction path — a parallel implementation could only drift away from this one.
   *
   * Reuses the existing instance when the path is unchanged: rebuilding would drop an initialized
   * Engine and pay the multi-second model load again for nothing.
   */
  private fun selectGemmaTier(target: ProviderTier, modelPath: String?): ProviderTier {
    // LiteRT-LM's native loader does its own fopen/mmap on modelPath — it doesn't understand the
    // "file://" URI scheme expo-file-system hands us (unlike verifyFileChecksum above, which strips
    // it via java.io.File). Without this, EngineConfig gets a literal "file://..." string and
    // LiteRtLmJniException reports the model file as not found.
    val path =
      (modelPath
          ?: throw IllegalArgumentException(
            "setProvider('${target.value}', ...) requires a modelPath",
          ))
        .removePrefix("file://")
    if (gemmaProviders[target] == null || gemmaModelPaths[target] != path) {
      gemmaProviders[target] = Gemma4BaselineProvider(context, path, target)
      gemmaModelPaths[target] = path
    }
    return target
  }

  private fun providerFor(t: ProviderTier): TranslationProvider =
    when (t) {
      ProviderTier.MOCK -> mockProvider
      ProviderTier.BASELINE, ProviderTier.FINE_TUNED ->
        gemmaProviders[t]
          ?: throw IllegalStateException(
            "${t.value} provider selected but no model path was set — " +
              "call setProvider('${t.value}', modelPath) first.",
          )
    }

  override fun definition() =
    ModuleDefinition {
      Name("ZaurelinkTranslate")

      // Streaming SHA-256 for the downloaded model file (TRD §2.4: verify before loading). Runs in
      // a coroutine off the main thread and hashes in 64KB chunks, so a ~2GB file never lands in
      // JS/heap memory. Accepts a raw path or a file:// URI from expo-file-system.
      AsyncFunction("verifyFileChecksum") Coroutine { path: String, expectedSha256: String ->
        val file = File(path.removePrefix("file://"))
        if (!file.exists()) {
          throw IllegalArgumentException("File not found: ${file.path}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
          }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        hex.equals(expectedSha256, ignoreCase = true)
      }

      Function("getProvider") { tier.value }

      // modelPath is required when switching to "baseline"/"fine_tuned" — the native side has no
      // independent knowledge of where JS downloaded the model (TRD §2.4 hand-off). Engine creation
      // itself is lazy (first startConversation call), so this just records the path.
      Function("setProvider") { name: String, modelPath: String? ->
        when (name) {
          "baseline" -> tier = selectGemmaTier(ProviderTier.BASELINE, modelPath)
          "fine_tuned" -> tier = selectGemmaTier(ProviderTier.FINE_TUNED, modelPath)
          else -> tier = ProviderTier.MOCK
        }
      }

      AsyncFunction("startConversation") Coroutine {
          environment: Environment,
          appUserLanguage: AppUserLanguage,
          maxWindowTurns: Int,
        ->
        val provider = providerFor(tier)
        val session = provider.startConversation(environment, appUserLanguage, maxWindowTurns)
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = provider to session
        sessionId
      }

      AsyncFunction("translate") Coroutine {
          sessionId: String,
          utteranceId: String?,
          textOverride: String?,
          activeChannel: ActiveChannel,
        ->
        val (provider, session) =
          sessions[sessionId]
            ?: throw IllegalStateException("Unknown session: $sessionId. Call startConversation first.")

        // Native hand-off (TRD §2.2): resolves to the audio module's retained PCM. One-shot — the
        // store removes the entry once taken, so a retried/duplicate call correctly gets null
        // rather than silently replaying stale audio.
        val pcm = utteranceId?.let { UtteranceStore.take(it) }

        provider.translate(session, pcm, textOverride, activeChannel)
      }

      AsyncFunction("endConversation") Coroutine { sessionId: String ->
        val entry = sessions.remove(sessionId)
        entry?.let { (provider, session) -> provider.endConversation(session) }
        Unit
      }
    }
}
