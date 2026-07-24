import { NativeModule, requireNativeModule } from 'expo';

import type {
  ActiveChannel,
  AppUserLanguage,
  Environment,
  ProviderTier,
  TranslationResult,
} from './ZaurelinkTranslate.types';

declare class ZaurelinkTranslateModule extends NativeModule<{}> {
  /** Streaming SHA-256 verify of the downloaded model file (TRD §2.4). Hashes in native chunks so a
   * ~2GB file never enters JS memory. Accepts a path or file:// URI. Resolves true on match. */
  verifyFileChecksum(path: string, expectedSha256: string): Promise<boolean>;
  /** Which of the three provider tiers is active (TRD §2.1). */
  getProvider(): ProviderTier;
  /** Switch tier. 'baseline' and 'fine_tuned' REQUIRE modelPath (the verified on-device .litertlm
   * path, e.g. from lib/modelManager.ts's getModelPath()) — first switch triggers LiteRT-LM Engine
   * creation, whose initialize() can take up to ~10s; that happens lazily on the first
   * startConversation() call after switching, not inside setProvider() itself. */
  setProvider(tier: ProviderTier, modelPath?: string): void;
  /** Starts a new bounded-memory conversation for the given environment and the app user's
   * selected language (TRD §2.2, FR-14). Called on Mode or Language selection/switch. */
  startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: number
  ): Promise<string>;
  /**
   * @param sessionId handle returned by startConversation
   * @param utteranceId id from onUtteranceReady keying the retained native PCM, or null for text input
   *   (raw PCM never crosses the bridge — the native side resolves this id to the ShortArray)
   * @param textOverride typed-input fallback / used directly by the Mock provider
   * @param activeChannel purely mechanical routing (TRD §2.1) — required output language is
   *   derived via requiredOutputLanguage(), never passed in directly
   */
  translate(
    sessionId: string,
    utteranceId: string | null,
    textOverride: string | null,
    activeChannel: ActiveChannel
  ): Promise<TranslationResult>;
  /** Ends the conversation and releases retained turn history. */
  endConversation(sessionId: string): Promise<void>;
}

export default requireNativeModule<ZaurelinkTranslateModule>('ZaurelinkTranslate');

export type { ActiveChannel, AppUserLanguage, Environment, ProviderTier, TranslationResult };
