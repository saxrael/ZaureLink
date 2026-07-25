import { ModelDownloadCard } from '@/components/ModelDownloadCard';
import { MicOrb, type OrbMode } from '@/components/MicOrb';
import { PressScale } from '@/components/PressScale';
import { Icon } from '@/components/ui/icon';
import { Text } from '@/components/ui/text';
import { TwoOptionToggle } from '@/components/TwoOptionToggle';
import { VoiceWave, type WaveMode } from '@/components/VoiceWave';
import { BRAND } from '@/lib/brand';
import { checkBattery } from '@/lib/deviceGuards';
import {
  FINE_TUNED_MODEL_CONFIG,
  getFineTunedModelPath,
  getModelPath,
  HAUSA_VOICE_CONFIG,
  MODEL_CONFIG,
  useModelDownload,
} from '@/lib/modelManager';
import ZaurelinkAudio from '@/modules/zaurelink-audio/src/ZaurelinkAudioModule';
import type { CaptureMode, EngineDiagnostics } from '@/modules/zaurelink-audio/src/ZaurelinkAudio.types';
import ZaurelinkTranslate from '@/modules/zaurelink-translate/src/ZaurelinkTranslateModule';
import {
  requiredOutputLanguage,
  type ActiveChannel,
  type AppUserLanguage,
  type Environment,
  type ProviderTier,
  type TranslationResult,
} from '@/modules/zaurelink-translate/src/ZaurelinkTranslate.types';
import ZaurelinkTts from '@/modules/zaurelink-tts/src/ZaurelinkTtsModule';
import { Stack } from 'expo-router';
import {
  ArrowRight,
  BluetoothOff,
  Download,
  Send,
  Settings2,
  Sun,
  Type as TypeIcon,
  Volume2,
  VolumeX,
  Wifi,
  X,
} from 'lucide-react-native';
import * as React from 'react';
import { AppState, Image, Modal, Platform, Pressable, ScrollView, TextInput, View } from 'react-native';
import Animated, { FadeIn, useSharedValue, withTiming } from 'react-native-reanimated';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

const LOGO_ICON = require('@/assets/images/zaurelink-icon.png');

// FR-12: translation text scales rather than assuming a fixed budget (Hausa/English differ in
// length). FR-03/illiteracy-safe: large type by default. Sizes are explicit so text never truncates.
type TextScale = 'normal' | 'large' | 'huge';
const TEXT_SCALES: TextScale[] = ['normal', 'large', 'huge'];
const TRANSLATION_FONT: Record<TextScale, number> = { normal: 22, large: 30, huge: 42 };
const SOURCE_FONT: Record<TextScale, number> = { normal: 15, large: 18, huge: 24 };

type Sensitivity = 'low' | 'medium' | 'high';
// Energy-VAD RMS thresholds (normalized 0..1). Higher sensitivity = lower threshold = triggers
// more easily. Field-tune against real market/campus recordings (TRD §3.1).
const SENSITIVITY_RMS: Record<Sensitivity, number> = { low: 0.03, medium: 0.015, high: 0.008 };

// Silero speech-probability thresholds (0..1) — a different scale from the RMS values above, so
// they need their own mapping. Silero's own documented default is 0.5, which in practice is too
// strict for a phone held at arm's length in a noisy place: the gate never opens and auto-listen
// looks broken. Medium sits below that default deliberately.
const SENSITIVITY_SILERO: Record<Sensitivity, number> = { low: 0.6, medium: 0.4, high: 0.25 };

// PRD §3.5: conversation session memory window (TRD §2.2 conversation_window_turns: 8).
const MAX_WINDOW_TURNS = 8;
// FR-09: end/reset a session after this much inactivity (no speech or translation from either party).
// Raised from 3min: a reset is not free. Starting a new conversation re-prefills the ~370-454 token
// system instruction, which the user experiences as a slow first turn — so a timeout short enough to
// fire during an ordinary lull (haggling over a price, waiting in a clinic queue, digging out a
// student ID) makes the app feel slow at exactly the moments it should feel responsive. 10min still
// resets a genuinely abandoned conversation before any stale context could bleed into a new one.
const INACTIVITY_MS = 10 * 60 * 1000;

type TranscriptEntry = TranslationResult & {
  id: string;
  environment: Environment;
  /** Who spoke this turn — purely mechanical routing, TRD §2.1. */
  activeChannel: ActiveChannel;
  /** Derived once at creation (requiredOutputLanguage) — which language the output is actually in. */
  outputLanguage: AppUserLanguage;
};

export default function Screen() {
  const [environment, setEnvironment] = React.useState<Environment>('market');
  // FR-14: the app user's own selected/required language — session-level, not per-turn.
  const [appUserLanguage, setAppUserLanguage] = React.useState<AppUserLanguage>('english');
  // TRD §4.3: forces ActiveChannel (who's speaking) when auto-detection from mic path misfires.
  // Has NO effect on appUserLanguage, which only changes by ending the session and selecting again.
  const [manualChannelOverride, setManualChannelOverride] = React.useState(false);
  const [manualChannel, setManualChannel] = React.useState<ActiveChannel>('earpod');
  const [sessionId, setSessionId] = React.useState<string | null>(null);
  const [inputText, setInputText] = React.useState('');
  const [transcript, setTranscript] = React.useState<TranscriptEntry[]>([]);
  const [isTranslating, setIsTranslating] = React.useState(false);

  const [isCapturing, setIsCapturing] = React.useState(false);
  const levelSV = useSharedValue(0); // mic amplitude 0..1, drives the orb + wave on the UI thread
  const [routing, setRouting] = React.useState('phone_mic_speaker');
  const [btDisconnected, setBtDisconnected] = React.useState(false);
  const [lastUtterance, setLastUtterance] = React.useState<string | null>(null);
  const [captureMode, setCaptureMode] = React.useState<CaptureMode>('push_to_talk');
  const [sensitivity, setSensitivity] = React.useState<Sensitivity>('medium');
  const [engineDiagnostics, setEngineDiagnostics] = React.useState<EngineDiagnostics>(null);
  const [provider, setProvider] = React.useState<ProviderTier>('mock');
  const [speakEnabled, setSpeakEnabled] = React.useState(true); // NFR-06: audio output is core
  const [hausaVoice, setHausaVoice] = React.useState<boolean | null>(null);
  const [hausaWifiOnly, setHausaWifiOnly] = React.useState(true);
  const [textScale, setTextScale] = React.useState<TextScale>('large'); // FR-12 large type default
  const [highContrast, setHighContrast] = React.useState(false); // FR-03 sunlight mode
  const [settingsOpen, setSettingsOpen] = React.useState(false); // keeps the home screen minimal
  const modelDownload = useModelDownload(MODEL_CONFIG); // lifted so the screen reacts to phase, not just the card
  const fineTunedDownload = useModelDownload(FINE_TUNED_MODEL_CONFIG); // TRD §2.4 artifact (~2.6GB)
  const voiceDownload = useModelDownload(HAUSA_VOICE_CONFIG); // Hausa MMS voice (~109MB)
  const [preparingModel, setPreparingModel] = React.useState(false); // Engine.initialize() can take ~10s
  // TRD §2.4 rollback: set if the fine-tuned artifact is present but its engine won't load. Demotes
  // the app back to the baseline instead of retrying a model that has already failed once.
  const [fineTunedUnusable, setFineTunedUnusable] = React.useState(false);

  // Refs so the mount-only audio-event listener always sees the latest values.
  const speakEnabledRef = React.useRef(speakEnabled);
  speakEnabledRef.current = speakEnabled;
  const sessionIdRef = React.useRef<string | null>(null);
  sessionIdRef.current = sessionId;
  const environmentRef = React.useRef(environment);
  environmentRef.current = environment;
  const appUserLanguageRef = React.useRef(appUserLanguage);
  appUserLanguageRef.current = appUserLanguage;

  // TRD §2.1: ActiveChannel resolved from the live routing state, unless manually forced (§4.3).
  // 'disconnected' has no mic active — fall back to phone_mic (matches the pre-connect default).
  const resolvedChannel: ActiveChannel = manualChannelOverride
    ? manualChannel
    : routing === 'earpod_mic'
      ? 'earpod'
      : 'phone_mic';
  const resolvedChannelRef = React.useRef(resolvedChannel);
  resolvedChannelRef.current = resolvedChannel;

  const captureModeRef = React.useRef(captureMode);
  captureModeRef.current = captureMode;
  // Counts committed utterances so endCapture() can tell "auto-listen produced nothing" apart from
  // "auto-listen worked" without racing the native event.
  const utteranceCountRef = React.useRef(0);

  // What the NEXT turn will actually do. Without a Bluetooth earpiece the app falls back to
  // phone_mic, which means "the other party is speaking" — so the output is the app user's OWN
  // language, not the opposite one. That's correct per TRD §2.1, but it is completely invisible
  // on screen, and it makes solo testing read as "it isn't translating" (speak English with
  // My language = English and English is exactly what you should get back). Surfacing the
  // resolved speaker + output language makes the routing legible instead of surprising.
  const nextOutputLanguage = requiredOutputLanguage(appUserLanguage, resolvedChannel);
  const nextSpeakerLabel =
    resolvedChannel === 'earpod' ? 'You' : environment === 'market' ? 'Trader' : 'Other party';

  // FR-09 inactivity timeout: reset the conversation (clears bounded memory/context, PRD §3.5)
  // after a stretch with no speech or translation. Any activity bumps the timer.
  const inactivityTimer = React.useRef<ReturnType<typeof setTimeout> | null>(null);
  const bumpActivity = React.useCallback(() => {
    if (inactivityTimer.current) clearTimeout(inactivityTimer.current);
    inactivityTimer.current = setTimeout(async () => {
      const sid = sessionIdRef.current;
      if (!sid) return;
      await ZaurelinkTranslate.endConversation(sid);
      const id = await ZaurelinkTranslate.startConversation(
        environmentRef.current,
        appUserLanguageRef.current,
        MAX_WINDOW_TURNS
      );
      setSessionId(id);
      setTranscript([]);
      setLastUtterance('New session started after inactivity.');
    }, INACTIVITY_MS);
  }, []);

  // Stable (refs inside) so the mount-only utterance listener can call it directly. Exactly one of
  // utteranceId / text is set: utteranceId for a captured utterance (PCM stays native), text for
  // typed input. Both go through the same provider path. Defined before the effects that use it.
  const performTranslate = React.useCallback(
    async (utteranceId: string | null, text: string | null, activeChannel: ActiveChannel) => {
      const sid = sessionIdRef.current;
      if (!sid) return;
      bumpActivity();
      setIsTranslating(true);
      try {
        const result = await ZaurelinkTranslate.translate(sid, utteranceId, text, activeChannel);
        const outputLanguage = requiredOutputLanguage(appUserLanguageRef.current, activeChannel);
        setTranscript((prev) => [
          {
            ...result,
            id: `${Date.now()}-${Math.random().toString(36).slice(2)}`,
            environment: environmentRef.current,
            activeChannel,
            outputLanguage,
          },
          ...prev,
        ]);
        // Speech-to-speech: speak the translation. On-screen text always stays as the failsafe
        // (NFR-06 / FR-06), so a missing voice (e.g. no Hausa engine) never blocks the result.
        if (speakEnabledRef.current) {
          ZaurelinkTts.speak(result.translatedText, outputLanguage).catch(() => {});
        }
      } catch (e) {
        setLastUtterance(`Translate failed: ${String(e)}`);
      } finally {
        setIsTranslating(false);
      }
    },
    [bumpActivity]
  );

  React.useEffect(() => {
    return () => {
      if (inactivityTimer.current) clearTimeout(inactivityTimer.current);
    };
  }, []);

  // PRD §3.5: switching either Environment or Language ends the current session and starts a new
  // one — both are genuine context changes, not a continuation.
  const startSession = React.useCallback(async (env: Environment, lang: AppUserLanguage) => {
    if (sessionIdRef.current) {
      await ZaurelinkTranslate.endConversation(sessionIdRef.current);
    }
    const id = await ZaurelinkTranslate.startConversation(env, lang, MAX_WINDOW_TURNS);
    setSessionId(id);
    setTranscript([]);
  }, []);

  React.useEffect(() => {
    startSession('market', 'english');
    return () => {
      if (sessionIdRef.current) {
        ZaurelinkTranslate.endConversation(sessionIdRef.current);
      }
    };
    // Mount-only: starts the first session. Environment/Language switches are handled explicitly below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Native audio events (Android). Web audio module is a benign stub, so guard the listeners.
  React.useEffect(() => {
    if (Platform.OS === 'web') return;
    const levelSub = ZaurelinkAudio.addListener('onAudioLevel', ({ level }) => {
      // Smooth the ~10Hz samples into a fluid amplitude for the orb + waveform.
      levelSV.value = withTiming(level, { duration: 120 });
    });
    const routeSub = ZaurelinkAudio.addListener('onRoutingChanged', ({ state }) => setRouting(state));
    const btSub = ZaurelinkAudio.addListener('onBluetoothDisconnected', () => {
      // Fail-safe already halted capture natively; reflect that in the UI and show the banner.
      setIsCapturing(false);
      levelSV.value = withTiming(0, { duration: 200 });
      setBtDisconnected(true);
    });
    const utterSub = ZaurelinkAudio.addListener(
      'onUtteranceReady',
      ({ utteranceId, durationMs, peakLevel }) => {
        utteranceCountRef.current += 1;
        setLastUtterance(`Captured ${durationMs}ms (peak ${(peakLevel * 100).toFixed(0)}%)`);
        // Full pipeline: talk -> VAD segments -> utterance -> translate -> transcript. The Mock
        // tier returns canned text (it acknowledges the audio sample count), so the WIRE is real
        // end-to-end even though the translation content is a stand-in until Phase 4's model.
        performTranslate(utteranceId, null, resolvedChannelRef.current);
      }
    );
    return () => {
      levelSub.remove();
      routeSub.remove();
      btSub.remove();
      utterSub.remove();
    };
  }, [performTranslate]);

  React.useEffect(() => {
    setProvider(ZaurelinkTranslate.getProvider());
  }, []);

  // Which model cards the screen offers. Keeping this here rather than inline in the JSX because the
  // rule it encodes is a product decision, not layout: a new install is asked for ONE ~2.6GB model.
  const baselineOnDisk = modelDownload.phase === 'ready';
  const baselineCardVisible =
    baselineOnDisk ||
    modelDownload.phase === 'downloading' ||
    modelDownload.phase === 'paused' ||
    modelDownload.phase === 'verifying' ||
    // The fine-tuned model failed: the baseline is now the only route to offline translation, so it
    // has to be reachable even on an install that never downloaded it.
    fineTunedUnusable ||
    modelDownload.phase === 'error';

  // TRD §2.1/§2.4 best-available tier: the fine-tuned artifact wins when it is on disk and verified,
  // the stock baseline is the fallback, and mock covers "no model yet" so the app is never dead
  // (FR-05). Derived rather than stored, so it re-resolves when a download finishes or a model turns
  // out to be unloadable.
  const desiredTier: ProviderTier | null =
    Platform.OS === 'web'
      ? null
      : fineTunedDownload.phase === 'ready' && !fineTunedUnusable
        ? 'fine_tuned'
        : modelDownload.phase === 'ready'
          ? 'baseline'
          : null;

  // Switch off mock automatically once a model is verified on-device, so translation is real without
  // any manual step. The first switch triggers LiteRT-LM's Engine.initialize() (~10s) inside the
  // startConversation() call below, which is already off the main thread — this effect just surfaces
  // that wait in the UI.
  React.useEffect(() => {
    if (!desiredTier || provider === desiredTier) return;
    let cancelled = false;
    (async () => {
      setPreparingModel(true);
      try {
        ZaurelinkTranslate.setProvider(
          desiredTier,
          desiredTier === 'fine_tuned' ? getFineTunedModelPath() : getModelPath()
        );
        await startSession(environmentRef.current, appUserLanguageRef.current);
        if (!cancelled) setProvider(desiredTier);
      } catch (e) {
        if (cancelled) return;
        // A fine-tuned model that won't load must not strand the app (TRD §2.4 rollback): mark it
        // unusable so this effect re-resolves onto the baseline. Without this the effect would loop
        // forever, since a failed switch leaves provider !== desiredTier.
        if (desiredTier === 'fine_tuned') {
          setFineTunedUnusable(true);
          setLastUtterance(
            `Fine-tuned model failed to load — falling back to the baseline model. ${String(e)}`
          );
        } else {
          setLastUtterance(`Could not switch to offline model: ${String(e)}`);
        }
      } finally {
        if (!cancelled) setPreparingModel(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [desiredTier, provider]);

  // Initialize TTS and check whether ANY installed engine has a Hausa voice (TRD §5). Note: no
  // real offline Hausa TTS is the MMS voice (facebook/mms-tts-hau) once its model is downloaded;
  // until then Hausa degrades to text. English is unaffected (system TTS).
  const refreshTtsState = React.useCallback(() => {
    setHausaVoice(ZaurelinkTts.isLanguageAvailable('hausa'));
  }, []);

  // Hand the downloaded MMS voice model to the native TTS engine once it's verified-ready.
  React.useEffect(() => {
    if (Platform.OS === 'web' || voiceDownload.phase !== 'ready') return;
    const loaded = ZaurelinkTts.setHausaVoiceModelPath(voiceDownload.modelPath);
    if (loaded) setHausaVoice(true);
  }, [voiceDownload.phase, voiceDownload.modelPath]);

  React.useEffect(() => {
    let cancelled = false;
    ZaurelinkTts.initialize()
      .then(() => {
        if (!cancelled) refreshTtsState();
      })
      .catch(() => {
        if (!cancelled) setHausaVoice(false);
      });
    const sub = AppState.addEventListener('change', (s) => {
      if (s === 'active') refreshTtsState();
    });
    return () => {
      cancelled = true;
      sub.remove();
      ZaurelinkTts.stop();
    };
  }, [refreshTtsState]);

  // Push VAD settings to native whenever they change (Android only; web audio is a stub).
  React.useEffect(() => {
    if (Platform.OS === 'web') return;
    ZaurelinkAudio.setCaptureMode(captureMode);
    // setCaptureMode creates the native capture manager on first call, so this is also the first
    // point the real (vs fallback) engine choice is knowable — surface it honestly (TRD §3.2/§3.3).
    setEngineDiagnostics(ZaurelinkAudio.getEngineDiagnostics());
  }, [captureMode]);

  React.useEffect(() => {
    if (Platform.OS === 'web') return;
    // TRANSITIONAL: setVadConfig gained a sileroThreshold parameter, but JS hot-reloads while the
    // native module only changes on a rebuild — so a dev client built before that change is still
    // exposing the 3-arg signature and throws "Received 4 arguments, but 3 was expected". Falling
    // back keeps an older dev build usable instead of hard-failing on mount. Delete this shim once
    // every install is past the 4-arg build; the fallback silently loses Silero tuning, which is
    // exactly the bug this change exists to fix.
    try {
      ZaurelinkAudio.setVadConfig(
        SENSITIVITY_RMS[sensitivity],
        SENSITIVITY_SILERO[sensitivity],
        4,
        20
      );
    } catch {
      (ZaurelinkAudio.setVadConfig as unknown as (r: number, s: number, m: number) => void)(
        SENSITIVITY_RMS[sensitivity],
        4,
        20
      );
    }
  }, [sensitivity]);

  // PRD §3.5: switching Environment or Language is a genuine context change -> starts a new session.
  const handleEnvironmentChange = async (env: Environment) => {
    if (env === environment) return;
    setEnvironment(env);
    await startSession(env, appUserLanguage);
  };

  const handleLanguageChange = async (lang: AppUserLanguage) => {
    if (lang === appUserLanguage) return;
    setAppUserLanguage(lang);
    await startSession(environment, lang);
  };

  const handleTranslateText = async () => {
    const t = inputText.trim();
    if (!t) return;
    setInputText('');
    await performTranslate(null, t, resolvedChannel);
  };

  const ensureMicPermission = async () => {
    const perm = await ZaurelinkAudio.getRecordingPermission();
    if (perm.granted) return true;
    const asked = await ZaurelinkAudio.requestRecordingPermission();
    if (!asked.granted) {
      setLastUtterance('Microphone permission denied.');
      return false;
    }
    return true;
  };

  const beginCapture = async () => {
    setBtDisconnected(false);
    if (!(await ensureMicPermission())) return;
    bumpActivity();
    // FR-08: warn (don't block) on low battery before an inference session.
    const battery = await checkBattery();
    if (!battery.ok) setLastUtterance(battery.message);
    try {
      await ZaurelinkAudio.startCapture(false); // phone mic/speaker by default until BT is exercised
      setIsCapturing(true);
    } catch (e) {
      setLastUtterance(`Could not start mic: ${String(e)}`);
    }
  };

  const endCapture = async () => {
    setIsCapturing(false);
    levelSV.value = withTiming(0, { duration: 200 });
    await ZaurelinkAudio.stopCapture();
    // Auto-listen commits on detected silence, so a session can legitimately end having produced
    // nothing — but with no feedback that is indistinguishable from the app being broken. Native
    // emits onUtteranceReady synchronously inside stopCapture(); the short delay is only to let
    // that event finish crossing the bridge before we conclude nothing arrived.
    if (captureModeRef.current === 'auto_vad') {
      const before = utteranceCountRef.current;
      setTimeout(() => {
        if (utteranceCountRef.current === before) {
          setLastUtterance('No speech detected. Raise mic sensitivity in Settings, or hold to talk.');
        }
      }, 400);
    }
    // The mock provider can't translate audio yet (Phase 4). Capture is proven via the
    // onUtteranceReady metadata; use the text box to exercise the translation path for now.
  };

  // Push-to-talk (Phase 2): hold to capture one utterance.
  // beginCapture() awaits a permission check + battery check before it ever calls native
  // startCapture(), so a quick tap can fire onPressOut before isCapturing has actually flipped
  // true. Gating on that stale state would skip stopCapture() entirely and leave the mic
  // recording with nothing left to stop it. Instead, track the in-flight start and have
  // onPressOut wait for it to finish before deciding to stop — native stopCapture() is already a
  // safe no-op if start never succeeded, so no extra guard is needed here.
  const captureStartRef = React.useRef<Promise<void> | null>(null);

  const handleMicPressIn = async () => {
    if (Platform.OS === 'web' || captureMode !== 'push_to_talk') return;
    captureStartRef.current = beginCapture();
    await captureStartRef.current;
  };

  const handleMicPressOut = async () => {
    if (Platform.OS === 'web' || captureMode !== 'push_to_talk') return;
    if (captureStartRef.current) await captureStartRef.current;
    await endCapture();
  };

  // Auto-VAD (Phase 3): toggle a listening session; utterances segment automatically.
  const handleMicToggle = async () => {
    if (Platform.OS === 'web' || captureMode !== 'auto_vad') return;
    if (isCapturing) await endCapture();
    else await beginCapture();
  };

  const handleCaptureModeChange = async (m: CaptureMode) => {
    if (m === captureMode) return;
    if (isCapturing) await endCapture();
    setCaptureMode(m);
  };

  const handleReconnectOptIn = async () => {
    setBtDisconnected(false);
    await ZaurelinkAudio.continueOnPhoneMicSpeaker();
    setIsCapturing(true);
  };

  // PRD FR-09: explicit "End Conversation" control.
  const handleEndConversation = async () => {
    if (!sessionId) return;
    await ZaurelinkTranslate.endConversation(sessionId);
    setSessionId(null);
    setTranscript([]);
  };

  const handleReplay = (entry: TranscriptEntry) => {
    ZaurelinkTts.speak(entry.translatedText, entry.outputLanguage).catch(() => {});
  };

  const cycleTextScale = () => {
    setTextScale((s) => TEXT_SCALES[(TEXT_SCALES.indexOf(s) + 1) % TEXT_SCALES.length]);
  };

  // Orb/wave state: processing wins over listening so the motion keeps flowing during inference.
  // preparingModel disables the mic entirely — the Engine is mid-initialize() (~10s), not ready
  // to accept a conversation turn yet.
  const orbMode: OrbMode =
    Platform.OS === 'web' || preparingModel
      ? 'disabled'
      : isTranslating
        ? 'processing'
        : isCapturing
          ? 'listening'
          : 'idle';
  const waveMode: WaveMode = isTranslating ? 'processing' : isCapturing ? 'listening' : 'idle';

  const heroCaption = preparingModel
    ? 'Preparing offline model…'
    : isTranslating
      ? 'Translating…'
      : isCapturing
        ? captureMode === 'push_to_talk'
          ? 'Listening… release to stop'
          : 'Listening — tap to stop'
        : captureMode === 'push_to_talk'
          ? 'Hold to talk'
          : 'Tap to start listening';

  const insets = useSafeAreaInsets();

  return (
    <>
      <Stack.Screen options={{ headerShown: false }} />
      <ScrollView
        className="flex-1 bg-background"
        contentContainerStyle={{
          padding: 20,
          paddingTop: insets.top + 16,
          gap: 18,
          paddingBottom: 40,
          flexGrow: 1,
        }}
        keyboardShouldPersistTaps="handled">
        {/* Header: brand mark + settings entry point. Every other control lives behind this button,
            so the home screen stays down to environment + the mic + the conversation. The button is
            labelled, not a bare gear: a first-time user in a market has no reason to know a gear
            glyph hides the listening mode and voice controls, and this is the only way into them. */}
        <View className="flex-row items-center justify-between">
          <View className="flex-row items-center gap-2.5">
            <Image source={LOGO_ICON} style={{ width: 36, height: 36, borderRadius: 10 }} />
            <Text style={{ color: BRAND.navy }} className="text-xl font-extrabold tracking-tight">
              ZaureLink
            </Text>
          </View>
          <PressScale
            onPress={() => setSettingsOpen(true)}
            className="h-11 flex-row items-center gap-2 rounded-full bg-secondary px-4"
            accessibilityRole="button"
            accessibilityLabel="Settings">
            <Icon as={Settings2} size={19} color={BRAND.navy} />
            <Text style={{ color: BRAND.navy }} className="text-sm font-semibold">
              Settings
            </Text>
          </PressScale>
        </View>

        {/* The fine-tuned artifact is THE offline model for a new install (TRD §2.4) — one ~2.6GB
            download, not two. The stock baseline is deliberately not offered alongside it: a fresh
            user asked to fetch both would move 5.2GB to end up with one working translator, and the
            fine-tuned model is the better of the two anyway. Baseline stays visible only where it
            earns its place — already on disk (installs predating the fine-tune, where it is the
            active model and the fine-tune is a genuine upgrade), mid-download, or as the fallback
            when the fine-tuned model has failed and something has to cover for it. */}
        {baselineCardVisible ? <ModelDownloadCard dl={modelDownload} /> : null}

        {!fineTunedUnusable ? (
          <ModelDownloadCard
            dl={fineTunedDownload}
            config={FINE_TUNED_MODEL_CONFIG}
            title={baselineOnDisk ? 'Fine-tuned translator' : 'Offline translation model'}
            blurb={
              baselineOnDisk
                ? 'Optional ~2.6GB upgrade: the ZaureLink-tuned model, trained on Hausa market and campus speech. The baseline model stays installed as a fallback.'
                : 'A one-time ~2.6GB download unlocks fully offline translation — the ZaureLink-tuned model, trained on Hausa market and campus speech. Demo mode works without it.'
            }
            readyLabel="Fine-tuned model active"
          />
        ) : null}

        {btDisconnected ? (
          <Animated.View
            entering={FadeIn.duration(180)}
            className="flex-row items-start gap-3 rounded-2xl border border-destructive/30 bg-destructive/10 p-4">
            <Icon as={BluetoothOff} size={20} className="mt-0.5 text-destructive" />
            <View className="flex-1 gap-2">
              <Text className="font-semibold text-foreground">Earpiece disconnected</Text>
              <Text className="text-sm text-muted-foreground">
                Paused for privacy. Reconnect to continue privately, or switch to the phone speaker
                (public).
              </Text>
              <PressScale
                onPress={handleReconnectOptIn}
                className="self-start rounded-full bg-foreground/5 px-4 py-2">
                <Text className="text-sm font-medium text-foreground">Continue on phone speaker</Text>
              </PressScale>
            </View>
          </Animated.View>
        ) : null}

        {/* Environment + Language (FR-02/FR-14) — both session-start choices, both trigger a new
            session on change, both front and center: neither is buried in settings. */}
        <View className="gap-1.5">
          <Text className="text-xs text-muted-foreground">Environment</Text>
          <TwoOptionToggle
            leftLabel="Market"
            rightLabel="Campus"
            value={environment === 'market' ? 'left' : 'right'}
            onChange={(v) => handleEnvironmentChange(v === 'left' ? 'market' : 'campus')}
          />
        </View>
        <View className="gap-1.5">
          <Text className="text-xs text-muted-foreground">My language</Text>
          <TwoOptionToggle
            leftLabel="Hausa"
            rightLabel="English"
            value={appUserLanguage === 'hausa' ? 'left' : 'right'}
            onChange={(v) => handleLanguageChange(v === 'left' ? 'hausa' : 'english')}
          />
        </View>

        {/* Hero: the reactive mic orb is the centerpiece of the whole screen. */}
        <View className="items-center gap-3 py-4">
          <MicOrb
            mode={orbMode}
            level={levelSV}
            onPressIn={handleMicPressIn}
            onPressOut={handleMicPressOut}
            onPress={handleMicToggle}
          />
          <VoiceWave level={levelSV} mode={waveMode} />
          {/* Makes the per-turn routing visible: who the app thinks is speaking, and which
              language the answer will come back in. */}
          <View className="flex-row items-center gap-1.5 rounded-full bg-muted px-3 py-1.5">
            <Text className="text-xs font-medium text-muted-foreground">{nextSpeakerLabel}</Text>
            <Icon as={ArrowRight} size={12} className="text-muted-foreground" />
            <Text className="text-xs font-semibold capitalize text-foreground">
              {nextOutputLanguage}
            </Text>
          </View>
          <Text className="text-center text-base font-medium text-foreground">{heroCaption}</Text>
          {lastUtterance ? (
            <Text className="text-center text-xs text-muted-foreground">{lastUtterance}</Text>
          ) : null}
        </View>

        {/* Type instead — compact pill row, secondary to voice. */}
        <View className="flex-row items-end gap-2">
          <TextInput
            value={inputText}
            onChangeText={setInputText}
            placeholder="Or type what was said…"
            placeholderTextColor="#9aa5b8"
            multiline
            className="max-h-28 flex-1 rounded-3xl bg-secondary px-4 py-3 text-base text-foreground"
          />
          <PressScale
            onPress={handleTranslateText}
            disabled={!sessionId || isTranslating || !inputText.trim()}
            className="h-12 w-12 items-center justify-center rounded-full bg-primary">
            <Icon as={Send} size={18} color={BRAND.navy} />
          </PressScale>
        </View>

        {/* Conversation transcript. */}
        {transcript.length > 0 ? (
          <View className="gap-3">
            <Text className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
              Conversation
            </Text>
            {transcript.map((entry) => (
              <TranscriptCard
                key={entry.id}
                entry={entry}
                textScale={textScale}
                highContrast={highContrast}
                onReplay={() => handleReplay(entry)}
              />
            ))}
          </View>
        ) : (
          <View className="items-center gap-1 py-6">
            <Text className="text-sm text-muted-foreground">Nothing yet</Text>
            <Text className="text-center text-xs text-muted-foreground">
              Talk, or type, to start a conversation.
            </Text>
          </View>
        )}
      </ScrollView>

      {/* Settings as a sheet over the screen, not a block appended to the end of the page. It used to
          render below the transcript inside the ScrollView, which meant tapping the header button
          appeared to do nothing at all — the panel opened off-screen and only a scroll to the bottom
          revealed it. A modal puts what was tapped in front of the user, and gives Android's back
          gesture a way to close it (onRequestClose). */}
      <Modal
        visible={settingsOpen}
        animationType="slide"
        transparent
        statusBarTranslucent
        onRequestClose={() => setSettingsOpen(false)}>
        <View className="flex-1 justify-end" style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
          {/* Tap-outside-to-dismiss. Sibling of the sheet rather than a wrapper around it, so
              presses inside the sheet can never bubble out and close it mid-interaction. */}
          <Pressable
            className="flex-1"
            onPress={() => setSettingsOpen(false)}
            accessibilityRole="button"
            accessibilityLabel="Close settings"
          />
          <View
            className="rounded-t-3xl bg-background"
            style={{ maxHeight: '88%', paddingBottom: insets.bottom }}>
            <View className="flex-row items-center justify-between border-b border-border px-5 py-4">
              <Text className="text-lg font-bold text-foreground">Settings</Text>
              <PressScale
                onPress={() => setSettingsOpen(false)}
                className="h-10 w-10 items-center justify-center rounded-full bg-secondary"
                accessibilityRole="button"
                accessibilityLabel="Close settings">
                <Icon as={X} size={19} color={BRAND.navy} />
              </PressScale>
            </View>

            <ScrollView
              contentContainerStyle={{ padding: 20, gap: 20 }}
              keyboardShouldPersistTaps="handled">
            <SettingsSection label="Channel">
              <ToggleRow
                label="Manual override"
                sub="Force who's speaking instead of auto-detecting from mic path"
                value={manualChannelOverride}
                onChange={setManualChannelOverride}
              />
              {manualChannelOverride ? (
                <TwoOptionToggle
                  leftLabel="You"
                  rightLabel="Other party"
                  value={manualChannel === 'earpod' ? 'left' : 'right'}
                  onChange={(v) => setManualChannel(v === 'left' ? 'earpod' : 'phone_mic')}
                />
              ) : null}
            </SettingsSection>

            <SettingsSection label="Listening mode">
              <TwoOptionToggle
                leftLabel="Hold to talk"
                rightLabel="Auto listen"
                value={captureMode === 'push_to_talk' ? 'left' : 'right'}
                onChange={(v) => handleCaptureModeChange(v === 'left' ? 'push_to_talk' : 'auto_vad')}
              />
              {captureMode === 'auto_vad' ? (
                <View className="flex-row gap-2">
                  {(['low', 'medium', 'high'] as const).map((s) => (
                    <Chip key={s} label={s} active={sensitivity === s} onPress={() => setSensitivity(s)} />
                  ))}
                </View>
              ) : null}
              {engineDiagnostics ? (
                <Text className="text-xs text-muted-foreground">
                  VAD: {engineDiagnostics.vad === 'silero' ? 'Silero' : 'Energy (fallback)'} · DSP:{' '}
                  {engineDiagnostics.dsp === 'webrtc_ns' ? 'WebRTC NS' : 'Passthrough (fallback)'}
                </Text>
              ) : null}
            </SettingsSection>

            <SettingsSection label="Voice output">
              <ToggleRow
                icon={speakEnabled ? Volume2 : VolumeX}
                label="Speak translations"
                value={speakEnabled}
                onChange={setSpeakEnabled}
              />
              {hausaVoice === false ? (
                <View className="gap-2 rounded-2xl bg-accent/60 p-3">
                  {voiceDownload.phase === 'downloading' || voiceDownload.phase === 'verifying' ? (
                    <Text className="text-xs text-accent-foreground">
                      {voiceDownload.phase === 'verifying'
                        ? 'Verifying Hausa voice…'
                        : `Downloading Hausa voice… ${Math.round(voiceDownload.progress * 100)}%`}
                    </Text>
                  ) : (
                    <>
                      <Text className="text-xs text-accent-foreground">
                        Hausa currently shows as text. Download the offline Hausa voice (~109MB, once)
                        to hear it spoken. English audio already works.
                      </Text>
                      {voiceDownload.error ? (
                        <Text className="text-xs text-destructive">{voiceDownload.error}</Text>
                      ) : null}
                      <View className="flex-row flex-wrap items-center gap-2">
                        <PressScale
                          onPress={() => setHausaWifiOnly((v) => !v)}
                          className={`flex-row items-center gap-1.5 rounded-full px-3 py-1.5 ${
                            hausaWifiOnly ? 'bg-primary' : 'bg-border'
                          }`}>
                          <Icon
                            as={Wifi}
                            size={13}
                            color={hausaWifiOnly ? BRAND.navy : undefined}
                            className={hausaWifiOnly ? '' : 'text-foreground'}
                          />
                          <Text
                            className={`text-xs font-medium ${hausaWifiOnly ? 'text-primary-foreground' : 'text-foreground'}`}>
                            Wi-Fi only
                          </Text>
                        </PressScale>
                        <PressScale
                          onPress={() => voiceDownload.start(hausaWifiOnly)}
                          className="flex-row items-center gap-2 self-start rounded-full bg-primary px-4 py-2">
                          <Icon as={Download} size={15} color={BRAND.navy} />
                          <Text className="text-sm font-semibold text-primary-foreground">
                            Download Hausa voice
                          </Text>
                        </PressScale>
                      </View>
                    </>
                  )}
                </View>
              ) : null}
            </SettingsSection>

            <SettingsSection label="Readability">
              <View className="flex-row items-center gap-2">
                <PressScale onPress={cycleTextScale} className="flex-row items-center gap-2 rounded-full bg-muted px-4 py-2.5">
                  <Icon as={TypeIcon} size={16} className="text-foreground" />
                  <Text className="text-sm font-medium capitalize text-foreground">{textScale}</Text>
                </PressScale>
                <PressScale
                  onPress={() => setHighContrast((v) => !v)}
                  className={`flex-row items-center gap-2 rounded-full px-4 py-2.5 ${highContrast ? 'bg-primary' : 'bg-muted'}`}>
                  <Icon as={Sun} size={16} color={highContrast ? BRAND.navy : undefined} className={highContrast ? '' : 'text-foreground'} />
                  <Text className={`text-sm font-medium ${highContrast ? 'text-primary-foreground' : 'text-foreground'}`}>
                    Sunlight
                  </Text>
                </PressScale>
              </View>
            </SettingsSection>

              <View className="flex-row items-center justify-between pt-1">
                <Text className="text-xs text-muted-foreground">
                  {preparingModel ? 'Preparing offline model…' : `Provider: ${provider}`}
                </Text>
                <PressScale
                  onPress={handleEndConversation}
                  disabled={!sessionId}
                  className="rounded-full px-4 py-2">
                  <Text className="text-sm font-medium text-destructive">End Conversation</Text>
                </PressScale>
              </View>
            </ScrollView>
          </View>
        </View>
      </Modal>
    </>
  );
}

function SettingsSection({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <View className="gap-2.5">
      <Text className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">{label}</Text>
      {children}
    </View>
  );
}

function ToggleRow({
  icon: IconComponent,
  label,
  sub,
  value,
  onChange,
}: {
  icon?: React.ComponentProps<typeof Icon>['as'];
  label: string;
  sub?: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <PressScale
      onPress={() => onChange(!value)}
      className="flex-row items-center justify-between rounded-2xl bg-muted px-4 py-3">
      <View className="flex-1 flex-row items-center gap-2.5">
        {IconComponent ? <Icon as={IconComponent} size={18} className="text-foreground" /> : null}
        <View className="flex-1">
          <Text className="text-sm font-medium text-foreground">{label}</Text>
          {sub ? <Text className="text-xs text-muted-foreground">{sub}</Text> : null}
        </View>
      </View>
      <View className={`h-6 w-11 justify-center rounded-full ${value ? 'bg-primary' : 'bg-border'}`}>
        <View
          className="h-5 w-5 rounded-full bg-white shadow-sm"
          style={{ marginLeft: value ? 22 : 2 }}
        />
      </View>
    </PressScale>
  );
}

function Chip({ label, active, onPress }: { label: string; active: boolean; onPress: () => void }) {
  return (
    <PressScale
      onPress={onPress}
      className={`rounded-full px-4 py-2 ${active ? 'bg-primary' : 'bg-muted'}`}>
      <Text
        className={`text-sm font-medium capitalize ${active ? 'text-primary-foreground' : 'text-foreground'}`}>
        {label}
      </Text>
    </PressScale>
  );
}

function TranscriptCard({
  entry,
  textScale,
  highContrast,
  onReplay,
}: {
  entry: TranscriptEntry;
  textScale: TextScale;
  highContrast: boolean;
  onReplay: () => void;
}) {
  return (
    <View
      className="rounded-2xl border border-border bg-card p-4"
      style={
        highContrast
          ? { backgroundColor: '#ffffff', borderColor: '#000000', borderWidth: 1 }
          : { shadowColor: BRAND.navy, shadowOpacity: 0.06, shadowRadius: 10, shadowOffset: { width: 0, height: 3 }, elevation: 1 }
      }>
      <View className="flex-row items-center justify-between">
        <Text
          className="text-xs text-muted-foreground"
          style={highContrast ? { color: '#444444' } : undefined}>
          {entry.environment === 'market' ? 'Market' : 'Campus'} ·{' '}
          {entry.activeChannel === 'earpod' ? 'You' : 'Other party'} · →{' '}
          {entry.outputLanguage === 'hausa' ? 'Hausa' : 'English'}
        </Text>
        <PressScale onPress={onReplay} className="h-8 w-8 items-center justify-center rounded-full bg-primary/15">
          <Icon as={Volume2} size={14} color={BRAND.orange} />
        </PressScale>
      </View>
      {entry.sourceTranscript ? (
        <Text
          className="mt-1.5 text-muted-foreground"
          style={{ fontSize: SOURCE_FONT[textScale], ...(highContrast ? { color: '#333333' } : null) }}>
          {entry.sourceTranscript}
        </Text>
      ) : null}
      <Text
        selectable
        className="mt-1 font-bold text-foreground"
        style={{
          fontSize: TRANSLATION_FONT[textScale],
          ...(highContrast ? { color: '#000000' } : null),
        }}>
        {entry.translatedText}
      </Text>
      {/* Diagnostics (TRD §1.1): the prefill/decode split is what tells you WHY a turn was slow —
          prefill-bound (long system prompt or audio tokens) vs decode-bound (long output). */}
      {formatLatency(entry.latencyBreakdownMs) ? (
        <Text
          className="mt-2 text-[10px] text-muted-foreground"
          style={highContrast ? { color: '#555555' } : undefined}>
          {formatLatency(entry.latencyBreakdownMs)}
        </Text>
      ) : null}
    </View>
  );
}

/** Compact one-liner, e.g. "3.4s · first token 1.2s · prefill 812tok @ 240/s · decode 24tok @ 9/s". */
function formatLatency(b: Record<string, number> | undefined): string | null {
  if (!b) return null;
  const total = b.total ?? b.baselineProvider ?? b.mockProvider;
  if (total == null) return null;
  const parts = [`${(total / 1000).toFixed(1)}s`];
  if (b.gpu != null) parts.push(b.gpu ? 'GPU' : 'CPU');
  if (b.timeToFirstTokenMs != null) {
    parts.push(`first token ${(b.timeToFirstTokenMs / 1000).toFixed(1)}s`);
  }
  if (b.prefillTokens != null) {
    parts.push(`prefill ${b.prefillTokens}tok @ ${b.prefillTokensPerSec ?? '?'}/s`);
  }
  if (b.decodeTokens != null) {
    parts.push(`decode ${b.decodeTokens}tok @ ${b.decodeTokensPerSec ?? '?'}/s`);
  }
  return parts.join(' · ');
}
