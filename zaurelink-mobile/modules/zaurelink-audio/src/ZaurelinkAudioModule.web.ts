import { registerWebModule, NativeModule } from 'expo';

import type {
  CaptureMode,
  EngineDiagnostics,
  RoutingState,
  ZaurelinkAudioModuleEvents,
} from './ZaurelinkAudio.types';

// Web has no AudioRecord / AudioManager routing. This is a benign stub so browser preview keeps
// working (the mock translate path is driven by the text input, not the mic). Native capture is
// Android-only in this project.
class ZaurelinkAudioModule extends NativeModule<ZaurelinkAudioModuleEvents> {
  async getRecordingPermission() {
    return this.denied();
  }

  async requestRecordingPermission() {
    return this.denied();
  }

  async requestBluetoothPermission() {
    return this.denied(); // no AudioManager/Bluetooth routing in the browser
  }

  hasBluetoothSco(): boolean {
    return false;
  }

  getRoutingState(): RoutingState {
    return 'phone_mic_speaker';
  }

  getEngineDiagnostics(): EngineDiagnostics {
    return null; // no native capture manager on web
  }

  setCaptureMode(_mode: CaptureMode): void {}

  setVadConfig(
    _rmsThreshold: number,
    _sileroThreshold: number,
    _minSpeechFrames: number,
    _minSilenceFrames: number
  ): void {}

  async startCapture(_preferBluetooth: boolean): Promise<void> {
    throw new Error('Audio capture is not available on web — use the text input for preview.');
  }

  async stopCapture(): Promise<void> {}

  async continueOnPhoneMicSpeaker(): Promise<void> {}

  private denied() {
    return {
      status: 'denied' as const,
      granted: false,
      canAskAgain: false,
      expires: 'never' as const,
    };
  }
}

export default registerWebModule(ZaurelinkAudioModule, 'ZaurelinkAudioModule');
