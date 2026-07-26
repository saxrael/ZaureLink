import { registerWebModule, NativeModule } from 'expo';

import {
  requiredOutputLanguage,
  type ActiveChannel,
  type AppUserLanguage,
  type Environment,
  type ProviderTier,
  type TranslationResult,
} from './ZaurelinkTranslate.types';

// Web has no LiteRT-LM/native audio path — this re-implements the same canned-response mock
// as the Android Kotlin MockTranslationProvider so the UI is never blank during browser preview.
const MOCK_RESPONSES: Record<string, string[]> = {
  'market:english': [
    'How much for this cloth?',
    "I can't reduce it any further, last price.",
    'Give me two hundred naira change.',
  ],
  'market:hausa': [
    'Nawa ne wannan?',
    'Ba zan iya rangwame ba, wannan shine farashin karshe.',
    'Ka ba ni sauran kudi na naira dari biyu.',
  ],
  'campus:english': [
    'I have been feeling feverish since yesterday.',
    'The Keke fare to the main gate is how much?',
    'My stomach has been hurting since morning.',
  ],
  'campus:hausa': [
    'Ina jin zazzabi tun jiya.',
    'Nawa ne kudin keke zuwa babbar kofa?',
    'Cikina na ciwo tun da safe.',
  ],
};

// Same crude heuristic as the Kotlin Mock (FR-13 pass-through simulation, text-only — no ASR here).
const HAUSA_CHARS = /[ƙƘɓɓɗƊāū]/;
const HAUSA_WORDS = new Set([
  'ba', 'ne', 'ce', 'kuma', 'amma', 'don', 'yau', 'gobe', 'nawa', 'ina', 'kudi', 'wannan', 'shine', 'zan', 'iya', 'kudin',
]);
const ENGLISH_WORDS = new Set([
  'the', 'is', 'are', 'you', 'how', 'much', 'please', 'this', 'that', 'for', 'and', 'can', 'give', 'my',
]);

function detectLanguage(text: string): AppUserLanguage | null {
  const lower = text.toLowerCase();
  let hausaScore = (lower.match(HAUSA_CHARS)?.length ?? 0) * 3;
  let englishScore = 0;
  for (const word of lower.match(/[a-zà-ÿ']+/g) ?? []) {
    if (HAUSA_WORDS.has(word)) hausaScore++;
    if (ENGLISH_WORDS.has(word)) englishScore++;
  }
  if (hausaScore === 0 && englishScore === 0) return null;
  if (hausaScore > englishScore) return 'hausa';
  if (englishScore > hausaScore) return 'english';
  return null;
}

type SessionState = {
  environment: Environment;
  appUserLanguage: AppUserLanguage;
  maxWindowTurns: number;
  turnHistory: string[];
};

class ZaurelinkTranslateModule extends NativeModule<{}> {
  private sessions = new Map<string, SessionState>();
  private tier: ProviderTier = 'mock';

  async verifyFileChecksum(_path: string, _expectedSha256: string): Promise<boolean> {
    // No local model file on web preview.
    return false;
  }

  getProvider(): ProviderTier {
    return this.tier;
  }

  setProvider(tier: ProviderTier, _modelPath?: string): void {
    // Web preview has no LiteRT-LM runtime — stays on mock regardless of what's requested.
    this.tier = tier === 'mock' ? 'mock' : this.tier;
  }

  supportsAudioInput(): boolean {
    return true; // mock accepts anything; web has no mic capture path anyway
  }

  async startConversation(
    environment: Environment,
    appUserLanguage: AppUserLanguage,
    maxWindowTurns: number
  ): Promise<string> {
    const sessionId = `web-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    this.sessions.set(sessionId, { environment, appUserLanguage, maxWindowTurns, turnHistory: [] });
    return sessionId;
  }

  async translate(
    sessionId: string,
    _utteranceId: string | null,
    textOverride: string | null,
    activeChannel: ActiveChannel
  ): Promise<TranslationResult> {
    const session = this.sessions.get(sessionId);
    if (!session) {
      throw new Error(`Unknown session: ${sessionId}. Call startConversation first.`);
    }

    const startedAt = Date.now();
    await new Promise((resolve) => setTimeout(resolve, 200));

    const outputLanguage = requiredOutputLanguage(session.appUserLanguage, activeChannel);
    const key = `${session.environment}:${outputLanguage}`;
    const pool = MOCK_RESPONSES[key] ?? [`(mock) translation unavailable for ${key}`];

    const alreadyCorrectLanguage = textOverride != null && detectLanguage(textOverride) === outputLanguage;
    const translatedText = alreadyCorrectLanguage
      ? `(pass-through, already ${outputLanguage}) ${textOverride}`
      : textOverride
        ? `(mock echo) ${textOverride}`
        : pool[session.turnHistory.length % pool.length];

    session.turnHistory.push(translatedText);
    if (session.turnHistory.length > session.maxWindowTurns) {
      session.turnHistory.shift();
    }

    return {
      translatedText,
      translatedAudio: null,
      sourceTranscript: textOverride,
      latencyBreakdownMs: { mockProvider: Date.now() - startedAt },
    };
  }

  async endConversation(sessionId: string): Promise<void> {
    this.sessions.delete(sessionId);
  }
}

export default registerWebModule(ZaurelinkTranslateModule, 'ZaurelinkTranslateModule');
