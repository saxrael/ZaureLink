import { PressScale } from '@/components/PressScale';
import { Icon } from '@/components/ui/icon';
import { Text } from '@/components/ui/text';
import { BRAND } from '@/lib/brand';
import { MODEL_CONFIG, type ModelDownloadState } from '@/lib/modelManager';
import { AlertCircle, CheckCircle2, Download, Pause, Wifi } from 'lucide-react-native';
import * as React from 'react';
import { View } from 'react-native';

const APPROX_GB = `${(MODEL_CONFIG.approxBytes / 1_000_000_000).toFixed(1)}GB`;

// FR-01 onboarding / asset-download UI. Rendered as a non-blocking banner so the app stays usable
// in mock mode while the model downloads (FR-05 — never a blank screen).
// `dl` is lifted up (useModelDownload called once in the screen) rather than called here, so the
// screen can also react to phase changes — e.g. auto-switching to the real translation provider
// once the model is ready.
export function ModelDownloadCard({ dl }: { dl: ModelDownloadState }) {
  const [wifiOnly, setWifiOnly] = React.useState(true);

  // No card while checking, on web, or once the offline model file is present + verified.
  if (dl.phase === 'checking' || dl.phase === 'unsupported') return null;

  if (dl.phase === 'ready') {
    return (
      <View className="flex-row items-center gap-2 rounded-2xl bg-muted/60 px-4 py-2.5">
        <Icon as={CheckCircle2} size={14} color={BRAND.orange} />
        <Text className="text-xs text-muted-foreground">Offline model ready</Text>
      </View>
    );
  }

  return (
    <View className="gap-3 rounded-2xl bg-muted/60 p-4">
      <Text className="font-semibold text-foreground">Offline translation model</Text>

      {dl.phase === 'absent' ? (
        <>
          <Text className="text-sm text-muted-foreground">
            A one-time ~{APPROX_GB} download unlocks fully offline translation. Demo mode works
            without it.
          </Text>
          <View className="flex-row items-center gap-2">
            <PressScale
              onPress={() => setWifiOnly((v) => !v)}
              className={`flex-row items-center gap-1.5 self-start rounded-full px-3 py-1.5 ${
                wifiOnly ? 'bg-primary' : 'bg-border'
              }`}>
              <Icon as={Wifi} size={13} color={wifiOnly ? BRAND.navy : undefined} className={wifiOnly ? '' : 'text-foreground'} />
              <Text className={`text-xs font-medium ${wifiOnly ? 'text-primary-foreground' : 'text-foreground'}`}>
                Wi-Fi only
              </Text>
            </PressScale>
          </View>
          <PressScale
            onPress={() => dl.start(wifiOnly)}
            className="flex-row items-center justify-center gap-2 self-start rounded-full bg-primary px-5 py-2.5">
            <Icon as={Download} size={16} color={BRAND.navy} />
            <Text className="text-sm font-semibold text-primary-foreground">Download ({APPROX_GB})</Text>
          </PressScale>
        </>
      ) : null}

      {dl.phase === 'downloading' ? (
        <>
          <ProgressBar value={dl.progress} />
          <View className="flex-row items-center justify-between">
            <Text className="text-sm text-muted-foreground">{Math.round(dl.progress * 100)}%</Text>
            <PressScale onPress={dl.pause} className="flex-row items-center gap-1.5 rounded-full px-3 py-1.5">
              <Icon as={Pause} size={13} className="text-primary" />
              <Text className="text-sm font-medium text-primary">Pause</Text>
            </PressScale>
          </View>
        </>
      ) : null}

      {dl.phase === 'paused' ? (
        <>
          <ProgressBar value={dl.progress} />
          <PressScale
            onPress={dl.resume}
            className="flex-row items-center justify-center gap-2 self-start rounded-full bg-primary px-5 py-2.5">
            <Icon as={Download} size={16} color={BRAND.navy} />
            <Text className="text-sm font-semibold text-primary-foreground">
              Resume ({Math.round(dl.progress * 100)}%)
            </Text>
          </PressScale>
        </>
      ) : null}

      {dl.phase === 'verifying' ? (
        <Text className="text-sm text-muted-foreground">Verifying download…</Text>
      ) : null}

      {dl.phase === 'error' ? (
        <>
          <View className="flex-row items-start gap-2">
            <Icon as={AlertCircle} size={16} className="mt-0.5 text-destructive" />
            <Text className="flex-1 text-sm text-destructive">{dl.error}</Text>
          </View>
          <PressScale
            onPress={() => dl.start(wifiOnly)}
            className="self-start rounded-full bg-primary px-5 py-2.5">
            <Text className="text-sm font-semibold text-primary-foreground">Retry</Text>
          </PressScale>
        </>
      ) : null}
    </View>
  );
}

function ProgressBar({ value }: { value: number }) {
  return (
    <View className="h-2 overflow-hidden rounded-full bg-border">
      <View
        className="h-2 rounded-full bg-primary"
        style={{ width: `${Math.min(100, Math.max(0, Math.round(value * 100)))}%` }}
      />
    </View>
  );
}
