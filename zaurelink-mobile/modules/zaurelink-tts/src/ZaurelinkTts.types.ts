// TRD §5.1 TtsLanguage. Audio output is a core, critical-path requirement (NFR-06); text remains a
// required parallel path so a runtime TTS failure never fully silences the app.

export type TtsLanguage = 'english' | 'hausa';

export type ZaurelinkTtsModuleEvents = {
  onTtsReady: (payload: { ready: boolean }) => void;
  onTtsStart: (payload: { id: string | null }) => void;
  onTtsDone: (payload: { id: string | null }) => void;
  onTtsError: (payload: { id: string | null }) => void;
};
