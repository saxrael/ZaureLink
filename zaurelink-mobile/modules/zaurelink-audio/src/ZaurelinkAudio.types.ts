// Contract for the Phase 2 audio capture + routing module (TRD §3/§4).
// Raw PCM is retained natively and never crosses the bridge per-frame — JS sees only the events
// below (throttled level, utterance metadata, and the fail-safe Bluetooth-disconnect signal).

export type RoutingState = 'earpod_mic' | 'phone_mic_speaker' | 'disconnected';

/** push_to_talk: whole hold = one utterance (Phase 2). auto_vad: DSP->VAD->commit-gate segments
 * utterances automatically while capture runs (Phase 3, TRD §3.1). */
export type CaptureMode = 'push_to_talk' | 'auto_vad';

/** Which VAD/DSP engine actually loaded on this device — 'silero'/'webrtc_ns' are the real
 * engines; 'energy'/'passthrough' are the safe fallbacks if a native lib failed to load. Null
 * until the capture manager has been created at least once (e.g. by setCaptureMode on mount). */
export type EngineDiagnostics = { vad: 'silero' | 'energy'; dsp: 'webrtc_ns' | 'passthrough' } | null;

export type ZaurelinkAudioModuleEvents = {
  /** Throttled (~10Hz) input level, 0..1, for a live mic meter. Never a per-frame PCM payload. */
  onAudioLevel: (payload: { level: number }) => void;
  /** Emitted per committed utterance (TRD §3.1 boundary). utteranceId keys the retained native PCM
   * for the translate hand-off — pass it to ZaurelinkTranslate.translate(). */
  onUtteranceReady: (payload: {
    utteranceId: string;
    sampleCount: number;
    durationMs: number;
    peakLevel: number;
  }) => void;
  /** Unexpected Bluetooth SCO disconnect. Capture is ALREADY halted (fail-safe) — the UI must
   * show the opt-in banner and require an explicit choice before continuing (PRD §3.3). */
  onBluetoothDisconnected: () => void;
  /** Active routing changed (TRD §4.1 state table). */
  onRoutingChanged: (payload: { state: RoutingState }) => void;
};
