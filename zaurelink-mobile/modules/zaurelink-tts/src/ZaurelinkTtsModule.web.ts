import { registerWebModule, NativeModule } from 'expo';

import type { TtsLanguage, ZaurelinkTtsModuleEvents } from './ZaurelinkTts.types';

// Web preview uses the browser Web Speech API. English is broadly supported; Hausa depends on the
// browser's installed voices (usually absent) — mirrors the native "degrade to text" behavior.
class ZaurelinkTtsModule extends NativeModule<ZaurelinkTtsModuleEvents> {
  private get synth(): SpeechSynthesis | null {
    return typeof window !== 'undefined' && 'speechSynthesis' in window ? window.speechSynthesis : null;
  }

  async initialize(): Promise<boolean> {
    return this.synth != null;
  }

  isLanguageAvailable(language: TtsLanguage): boolean {
    return this.synth != null && language === 'english';
  }

  setHausaVoiceModelPath(_path: string): boolean {
    return false; // no on-device ONNX voice on web
  }

  // _toLoudspeaker is accepted for signature parity only: the browser has no concept of routing to a
  // specific output device, and the dual-channel scenario it exists for is Android-only.
  async speak(text: string, language: TtsLanguage, _toLoudspeaker = false): Promise<boolean> {
    const synth = this.synth;
    if (!synth) return false;
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = language === 'hausa' ? 'ha' : 'en-US';
    synth.speak(utterance);
    return true;
  }

  stop(): void {
    this.synth?.cancel();
  }
}

export default registerWebModule(ZaurelinkTtsModule, 'ZaurelinkTtsModule');
