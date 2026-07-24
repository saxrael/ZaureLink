package expo.modules.zaurelinktranslate

/**
 * TRD §2.1 "Fine-Tuned Provider" — final swap-in, NOT yet wired. Your LoRA-merged,
 * dynamic_wi4_afp32-quantized export, produced via `litert-torch export_hf` (TRD §2.3) from a
 * checkpoint already merged with model.merge_and_unload().
 *
 * Identical LiteRT-LM integration to [Gemma4BaselineProvider] — the only difference is which
 * .litertlm file it points at. TRD §2.4 hand-off contract when the artifact is ready:
 *   - external URL (the ~1.7–2GB file is kept out of git),
 *   - a SHA-256 checksum, verified before loading,
 *   - a versioned filename, e.g. zaurelink-gemma4-e2b-lora-v1.litertlm.
 * Keep the previous version's URL as a rollback until the new one is confirmed working on-device —
 * a failed last-minute retrain must never silently replace a working demo build.
 */
class Gemma4FineTunedProvider : TranslationProvider {
  override val tier = ProviderTier.FINE_TUNED

  override fun startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: Int,
  ): ConversationSession =
    throw NotImplementedError(
      "Gemma4FineTunedProvider not wired — needs the LoRA-merged .litertlm export (see TRD §2.4).",
    )

  override suspend fun translate(
    session: ConversationSession,
    audioPcm16kMono: ShortArray?,
    textOverride: String?,
    activeChannel: ActiveChannel,
  ): TranslationResultRecord =
    throw NotImplementedError("Gemma4FineTunedProvider not wired — see class docs (final swap-in).")

  override fun endConversation(session: ConversationSession) {}
}
