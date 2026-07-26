import { NativeModule, requireNativeModule } from 'expo';
import type { PermissionResponse } from 'expo-modules-core';

import type {
  CaptureMode,
  EngineDiagnostics,
  RoutingState,
  ZaurelinkAudioModuleEvents,
} from './ZaurelinkAudio.types';

declare class ZaurelinkAudioModule extends NativeModule<ZaurelinkAudioModuleEvents> {
  getRecordingPermission(): Promise<PermissionResponse>;
  requestRecordingPermission(): Promise<PermissionResponse>;
  /** BLUETOOTH_CONNECT (API 31+). Required to enumerate audio devices at all — without it a paired
   * earpiece is invisible. Requested separately from the mic so denying it never reports the
   * microphone as denied; the app stays fully usable on the phone's own mic and speaker. */
  requestBluetoothPermission(): Promise<PermissionResponse>;
  /** True if a Bluetooth SCO output device is currently available for the private channel. */
  hasBluetoothSco(): boolean;
  getRoutingState(): RoutingState;
  /** Which VAD/DSP engine actually loaded on this device — null until capture has been created at
   * least once (e.g. by setCaptureMode on mount). Surfaces a native-lib load failure honestly
   * instead of it silently degrading to the fallback with no visibility. */
  getEngineDiagnostics(): EngineDiagnostics;
  /** Set utterance-segmentation strategy (default push_to_talk). Takes effect on next startCapture. */
  setCaptureMode(mode: CaptureMode): void;
  /** Field-tune the VAD (TRD §3.1). rmsThreshold is normalized 0..1 for the energy baseline. */
  /** rmsThreshold drives the energy baseline; sileroThreshold (0..1 speech probability) drives the
   * neural engine. Separate scales — one value cannot tune both. */
  setVadConfig(
    rmsThreshold: number,
    sileroThreshold: number,
    minSpeechFrames: number,
    minSilenceFrames: number
  ): void;
  /** Start push-to-talk capture. Routes to the private earpod channel when preferBluetooth and an
   * SCO device is present, else the public phone mic/speaker channel (TRD §4.1). */
  startCapture(preferBluetooth: boolean): Promise<void>;
  /** Stop capture; emits onUtteranceReady with the captured utterance's metadata. */
  stopCapture(): Promise<void>;
  /** Explicit opt-in after a Bluetooth disconnect — continue on the public phone mic/speaker. */
  continueOnPhoneMicSpeaker(): Promise<void>;
}

export default requireNativeModule<ZaurelinkAudioModule>('ZaurelinkAudio');

export type { CaptureMode, EngineDiagnostics, RoutingState, ZaurelinkAudioModuleEvents };
