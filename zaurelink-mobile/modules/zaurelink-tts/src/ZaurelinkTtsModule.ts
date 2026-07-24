import { NativeModule, requireNativeModule } from 'expo';

import type { TtsLanguage, ZaurelinkTtsModuleEvents } from './ZaurelinkTts.types';

declare class ZaurelinkTtsModule extends NativeModule<ZaurelinkTtsModuleEvents> {
  /** Initialize the system TTS engine (async). Resolves true when a usable engine is ready. */
  initialize(): Promise<boolean>;
  /** True if a voice for this language is available (English via system TTS; Hausa via the MMS
   * voice once its model is set). */
  isLanguageAvailable(language: TtsLanguage): boolean;
  /** Point the Hausa voice at a downloaded MMS model file (facebook/mms-tts-hau ONNX). Returns true
   * if it loaded. Call after the voice-model download completes + verifies. */
  setHausaVoiceModelPath(path: string): boolean;
  /** Speak text. Resolves true if speech started; false if not ready or no installed voice
   * (caller MUST then fall back to on-screen text — NFR-06 / FR-06). */
  speak(text: string, language: TtsLanguage): Promise<boolean>;
  stop(): void;
}

export default requireNativeModule<ZaurelinkTtsModule>('ZaurelinkTts');

export type { TtsLanguage };
