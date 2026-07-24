// Mirrors the TranslationProvider contract in TRD §2.2 (Kotlin interface), adapted for the
// JS<->native module boundary: a live ConversationSession object can't cross the bridge, so
// startConversation returns an opaque session id string that translate()/endConversation()
// take as a handle instead.

export type Environment = 'market' | 'campus';

/** TRD §2.1: the app user's own selected/required language — session-level (FR-14), set once at
 * startConversation, fixed until the session ends. NOT per-turn — see ActiveChannel for that. */
export type AppUserLanguage = 'hausa' | 'english';

/** Purely mechanical: which mic captured this turn. Carries NO language information — never
 * assume 'earpod' means English or 'phone_mic' means Hausa (TRD §2.1). */
export type ActiveChannel = 'earpod' | 'phone_mic';

/** Derives which language a turn's output must be in — never stored, always computed. 'earpod'
 * (app user speaking) → the other party is listening, needs the opposite of appUserLanguage.
 * 'phone_mic' (other party speaking) → the app user is listening, needs their own language. */
export function requiredOutputLanguage(
  appUserLanguage: AppUserLanguage,
  activeChannel: ActiveChannel
): AppUserLanguage {
  if (activeChannel === 'earpod') {
    return appUserLanguage === 'hausa' ? 'english' : 'hausa';
  }
  return appUserLanguage;
}

/** TRD §2.1 three-tier provider strategy. Only 'mock' is wired today. */
export type ProviderTier = 'mock' | 'baseline' | 'fine_tuned';

export type TranslationResult = {
  translatedText: string;
  /** PCM16 mono samples, or null if TTS is unavailable/not yet wired (TRD §2.2) — UI must degrade gracefully. */
  translatedAudio: number[] | null;
  /** Best-effort ASR transcript of what was said, for on-screen confirmation. */
  sourceTranscript: string | null;
  /** Per-stage timing for demo diagnostics (TRD §1.1 latency ring buffer). */
  latencyBreakdownMs: Record<string, number>;
};
