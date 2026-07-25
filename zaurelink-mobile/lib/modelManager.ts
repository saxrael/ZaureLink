import ZaurelinkTranslate from '@/modules/zaurelink-translate/src/ZaurelinkTranslateModule';
import { checkBattery } from '@/lib/deviceGuards';
import * as FileSystem from 'expo-file-system/legacy';
import * as Network from 'expo-network';
import * as React from 'react';
import { Platform } from 'react-native';

// FR-01 asset-download manager (PRD §3.1). Large assets are NOT bundled in the APK (NFR-03); they
// are downloaded once, post-install, verified by SHA-256 (TRD §2.4), then loaded from local
// storage. The same logic serves the Gemma model and the Hausa voice via a config.

export type AssetConfig = {
  url: string;
  sha256: string;
  fileName: string;
  approxBytes: number;
};

// Gemma 4 E2B (int4), the standard CPU/GPU litert-community build — NOT the "-Web" file nor the
// chip-specific NPU builds. sha256 + size are the real values from the repo's git-LFS pointer.
// ⚠ If this URL 401s the repo is gated → re-host it. Audio-input support is still to be confirmed
// on-device (text path works regardless). Swap to your fine-tuned export later (TRD §2.4).
export const MODEL_CONFIG: AssetConfig = {
  url: 'https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true',
  sha256: '181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c',
  fileName: 'gemma-4-E2B-it.litertlm',
  approxBytes: 2_588_147_712, // 2.59GB
};

// TRD §2.4 fine-tuned artifact: the LoRA-merged export of the same Gemma 4 E2B base, so this is a
// full model file (2.56GB) rather than an adapter — it REPLACES the baseline .litertlm, it does not
// layer on top of it. sha256 supplied with the artifact; size + public reachability confirmed by an
// HTTP HEAD against the resolve URL (repo commit a8b8e37). Verified on-device before loading, same
// as every other asset — the HF ETag is a Xet content hash and cannot stand in for SHA-256.
// ⚠ Keep MODEL_CONFIG's baseline as the rollback path: if this artifact fails verification or
// underperforms, the app must still have a working offline translator (TRD §2.4).
export const FINE_TUNED_MODEL_CONFIG: AssetConfig = {
  url: 'https://huggingface.co/israel-ayeni/ZaureLink/resolve/main/zaurelink-translator-v1.litertlm?download=true',
  sha256: '48ee9a559e748dc08b9938733b53fd09d75f02d4c65114ba4b9f24795d1013bd',
  fileName: 'zaurelink-translator-v1.litertlm',
  approxBytes: 2_561_136_592, // 2.56GB (HEAD content-length)
};

// Hausa TTS voice: Meta MMS (facebook/mms-tts-hau), 36M VITS, exported to ONNX (willwade mirror).
// Run on-device via ONNX Runtime to speak Gemma's Hausa output. sha256 + size verified by download.
export const HAUSA_VOICE_CONFIG: AssetConfig = {
  url: 'https://huggingface.co/willwade/mms-tts-multilingual-models-onnx/resolve/main/hau/model.onnx?download=true',
  sha256: '24e0f5b88b35e36975ed3b69c065e571e51fdf612c72a26bc5048bfecc1d532c',
  fileName: 'mms-tts-hau.onnx',
  approxBytes: 114_013_880, // ~109MB
};

export type ModelPhase =
  | 'checking'
  | 'absent'
  | 'downloading'
  | 'paused'
  | 'verifying'
  | 'ready'
  | 'error'
  | 'unsupported'; // web preview

const MODEL_DIR = (FileSystem.documentDirectory ?? '') + 'models/';
const STORAGE_HEADROOM = 1.1; // require 10% over the asset size before starting
const RESUME_STATE_PERSIST_INTERVAL_MS = 3000; // throttle disk writes during active download

export function getAssetPath(config: AssetConfig): string {
  return MODEL_DIR + config.fileName;
}

/** Absolute path handed to LiteRT-LM's EngineConfig(modelPath=…) for the Gemma provider. */
export function getModelPath(): string {
  return getAssetPath(MODEL_CONFIG);
}

/** Same, for the fine-tuned artifact (TRD §2.4). Separate file, so both can sit on disk at once and
 * the baseline stays available as a rollback. */
export function getFineTunedModelPath(): string {
  return getAssetPath(FINE_TUNED_MODEL_CONFIG);
}

export type ModelDownloadState = {
  phase: ModelPhase;
  progress: number; // 0..1
  error: string | null;
  freeBytes: number | null;
  isWifi: boolean | null;
  start: (wifiOnly: boolean) => Promise<void>;
  pause: () => Promise<void>;
  resume: () => Promise<void>;
  refresh: () => Promise<void>;
  modelPath: string;
};

export function useModelDownload(config: AssetConfig): ModelDownloadState {
  const modelUri = getAssetPath(config);
  const verifiedFlagUri = modelUri + '.verified';
  // FR-01 "pause/resume support": a multi-GB download getting killed by the OS (not just an
  // explicit pause) is the realistic failure mode, not the exception — persisting the resumable
  // state to disk lets a download interrupted by an app kill continue from where it left off on
  // next launch, instead of restarting a ~2.6GB transfer from zero.
  const resumeStateUri = modelUri + '.resumestate.json';

  const [phase, setPhase] = React.useState<ModelPhase>('checking');
  const [progress, setProgress] = React.useState(0);
  const [error, setError] = React.useState<string | null>(null);
  const [freeBytes, setFreeBytes] = React.useState<number | null>(null);
  const [isWifi, setIsWifi] = React.useState<boolean | null>(null);
  const downloadRef = React.useRef<FileSystem.DownloadResumable | null>(null);
  const lastPersistRef = React.useRef(0);

  const persistResumeState = React.useCallback(
    async (dl: FileSystem.DownloadResumable) => {
      try {
        await FileSystem.writeAsStringAsync(resumeStateUri, JSON.stringify(dl.savable()));
      } catch {
        // Best-effort: losing this just means a future app-kill can't resume, not a functional failure.
      }
    },
    [resumeStateUri]
  );

  const clearResumeState = React.useCallback(async () => {
    await FileSystem.deleteAsync(resumeStateUri, { idempotent: true });
  }, [resumeStateUri]);

  const onProgress = React.useCallback(
    ({ totalBytesWritten, totalBytesExpectedToWrite }: FileSystem.DownloadProgressData) => {
      if (totalBytesExpectedToWrite > 0) {
        setProgress(totalBytesWritten / totalBytesExpectedToWrite);
      }
      const now = Date.now();
      const dl = downloadRef.current;
      if (dl && now - lastPersistRef.current > RESUME_STATE_PERSIST_INTERVAL_MS) {
        lastPersistRef.current = now;
        persistResumeState(dl);
      }
    },
    [persistResumeState]
  );

  const verify = React.useCallback(async () => {
    setPhase('verifying');
    try {
      const ok = await ZaurelinkTranslate.verifyFileChecksum(modelUri, config.sha256);
      await clearResumeState(); // download is done (good or bad) — a stale resume token is useless either way
      if (ok) {
        await FileSystem.writeAsStringAsync(verifiedFlagUri, new Date().toISOString());
        setPhase('ready');
      } else {
        await FileSystem.deleteAsync(modelUri, { idempotent: true });
        setError('Checksum mismatch — the download was corrupted. Please retry.');
        setPhase('error');
      }
    } catch (e) {
      setError(`Verification failed: ${String(e)}`);
      setPhase('error');
    }
  }, [modelUri, verifiedFlagUri, config.sha256, clearResumeState]);

  const refresh = React.useCallback(async () => {
    if (Platform.OS === 'web') {
      setPhase('unsupported');
      return;
    }
    setPhase('checking');
    const model = await FileSystem.getInfoAsync(modelUri);
    const verified = await FileSystem.getInfoAsync(verifiedFlagUri);
    if (model.exists && verified.exists) {
      setPhase('ready');
      return;
    }

    // Reconstruct an interrupted download from persisted state, if any, so resume() can continue
    // it directly rather than the user needing to restart from zero after an app kill.
    const resumeInfo = await FileSystem.getInfoAsync(resumeStateUri);
    if (resumeInfo.exists) {
      try {
        const saved = JSON.parse(await FileSystem.readAsStringAsync(resumeStateUri));
        downloadRef.current = FileSystem.createDownloadResumable(
          saved.url,
          saved.fileUri,
          saved.options,
          onProgress,
          saved.resumeData
        );
        setPhase('paused');
        return;
      } catch {
        await clearResumeState();
      }
    }
    setPhase('absent');
  }, [modelUri, verifiedFlagUri, resumeStateUri, onProgress, clearResumeState]);

  React.useEffect(() => {
    refresh();
  }, [refresh]);

  const start = React.useCallback(
    async (wifiOnly: boolean) => {
      if (Platform.OS === 'web') return;
      setError(null);

      if (config.url.includes('REPLACE_ME') || config.sha256.includes('REPLACE_ME')) {
        setError('Source not configured yet — set url and sha256 in lib/modelManager.ts.');
        setPhase('error');
        return;
      }

      try {
        await FileSystem.makeDirectoryAsync(MODEL_DIR, { intermediates: true }).catch(() => {});
        await clearResumeState(); // a fresh start invalidates any prior resume token

        // FR-08 battery guard: don't start a large download on a low, unplugged battery.
        const battery = await checkBattery();
        if (!battery.ok) {
          setError(battery.message);
          setPhase('error');
          return;
        }

        // FR-08 storage guard: require the asset size plus headroom before starting.
        const free = await FileSystem.getFreeDiskStorageAsync();
        setFreeBytes(free);
        if (free < config.approxBytes * STORAGE_HEADROOM) {
          setError(
            `Not enough free storage. Need ~${formatSize(config.approxBytes)}, have ${formatSize(free)}.`
          );
          setPhase('error');
          return;
        }

        // Wi-Fi-only default (PRD §3.1). Toggle off to allow cellular.
        const net = await Network.getNetworkStateAsync();
        const wifi = net.type === Network.NetworkStateType.WIFI;
        setIsWifi(wifi);
        if (wifiOnly && !wifi) {
          setError('On mobile data. Connect to Wi-Fi, or turn off "Wi-Fi only" to continue.');
          setPhase('error');
          return;
        }

        setProgress(0);
        setPhase('downloading');
        const dl = FileSystem.createDownloadResumable(config.url, modelUri, {}, onProgress);
        downloadRef.current = dl;
        const result = await dl.downloadAsync();
        if (!result) return; // paused mid-flight; resume() continues
        await verify();
      } catch (e) {
        setError(`Download failed: ${String(e)}`);
        setPhase('error');
      }
    },
    [config.url, config.sha256, config.approxBytes, modelUri, onProgress, verify, clearResumeState]
  );

  const pause = React.useCallback(async () => {
    const dl = downloadRef.current;
    if (!dl) return;
    await dl.pauseAsync();
    await persistResumeState(dl);
    setPhase('paused');
  }, [persistResumeState]);

  const resume = React.useCallback(async () => {
    const dl = downloadRef.current;
    if (!dl) return;
    setPhase('downloading');
    try {
      const result = await dl.resumeAsync();
      if (result) await verify();
    } catch (e) {
      setError(`Resume failed: ${String(e)}`);
      setPhase('error');
    }
  }, [verify]);

  return { phase, progress, error, freeBytes, isWifi, start, pause, resume, refresh, modelPath: modelUri };
}

function formatSize(bytes: number): string {
  return bytes >= 1_000_000_000
    ? `${(bytes / 1_000_000_000).toFixed(1)}GB`
    : `${Math.round(bytes / 1_000_000)}MB`;
}
