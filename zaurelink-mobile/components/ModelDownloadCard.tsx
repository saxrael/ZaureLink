import { PressScale } from '@/components/PressScale';
import { Icon } from '@/components/ui/icon';
import { Text } from '@/components/ui/text';
import { BRAND } from '@/lib/brand';
import { MODEL_CONFIG, type AssetConfig, type ModelDownloadState } from '@/lib/modelManager';
import { AlertCircle, CheckCircle2, Download, Pause, Wifi } from 'lucide-react-native';
import * as React from 'react';
import { View } from 'react-native';

// FR-01 onboarding / asset-download UI. Rendered as a non-blocking banner so the app stays usable
// in mock mode while the model downloads (FR-05 — never a blank screen).
// `dl` is lifted up (useModelDownload called once in the screen) rather than called here, so the
// screen can also react to phase changes — e.g. auto-switching to the real translation provider
// once the model is ready.
//
// `config`/`title`/`blurb` are parameterized because there is now more than one multi-GB .litertlm
// to fetch (the baseline and the TRD §2.4 fine-tuned artifact). They default to the baseline so the
// original call site is unchanged — the alternative, a second near-identical card, would mean every
// future download-UX fix has to be made twice.
export function ModelDownloadCard({
  dl,
  config = MODEL_CONFIG,
  title = 'Offline translation model',
  blurb,
  readyLabel = 'Offline model ready',
}: {
  dl: ModelDownloadState;
  config?: AssetConfig;
  title?: string;
  blurb?: string;
  readyLabel?: string;
}) {
  const [wifiOnly, setWifiOnly] = React.useState(true);
  const APPROX_GB = `${(config.approxBytes / 1_000_000_000).toFixed(1)}GB`;

  // Rendered in every state that can start or continue a transfer — not just `absent`. It used to
  // appear only on the first-run card, so a download that failed with "turn off Wi-Fi only to
  // continue" gave the user no way to do that: the retry button re-ran with the same setting and
  // produced the same error, with no route out except finding Wi-Fi.
  const wifiToggle = (
    <View className="flex-row items-center gap-2">
      <PressScale
        onPress={() => setWifiOnly((v) => !v)}
        accessibilityRole="button"
        accessibilityLabel={wifiOnly ? 'Wi-Fi only, on' : 'Wi-Fi only, off'}
        className={`flex-row items-center gap-1.5 self-start rounded-full px-3 py-1.5 ${
          wifiOnly ? 'bg-primary' : 'bg-border'
        }`}>
        <Icon
          as={Wifi}
          size={13}
          color={wifiOnly ? BRAND.navy : undefined}
          className={wifiOnly ? '' : 'text-foreground'}
        />
        <Text
          className={`text-xs font-medium ${wifiOnly ? 'text-primary-foreground' : 'text-foreground'}`}>
          Wi-Fi only
        </Text>
      </PressScale>
    </View>
  );

  // No card while checking, on web, or once the offline model file is present + verified.
  if (dl.phase === 'checking' || dl.phase === 'unsupported') return null;

  if (dl.phase === 'ready') {
    return (
      <View className="flex-row items-center gap-2 rounded-2xl bg-muted/60 px-4 py-2.5">
        <Icon as={CheckCircle2} size={14} color={BRAND.orange} />
        <Text className="text-xs text-muted-foreground">{readyLabel}</Text>
      </View>
    );
  }

  return (
    <View className="gap-3 rounded-2xl bg-muted/60 p-4">
      <Text className="font-semibold text-foreground">{title}</Text>

      {dl.phase === 'absent' ? (
        <>
          <Text className="text-sm text-muted-foreground">
            {blurb ??
              `A one-time ~${APPROX_GB} download unlocks fully offline translation. Demo mode works without it.`}
          </Text>
          {wifiToggle}
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
          {wifiToggle}
          <PressScale
            onPress={() => dl.resume(wifiOnly)}
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
          {dl.canResume ? <ProgressBar value={dl.progress} /> : null}
          {wifiToggle}
          {/* Continuing is the primary action whenever there are bytes on disk worth keeping —
              a dropped connection partway through a ~2.6GB transfer must not cost the whole
              transfer. Starting over stays available, but demoted, because it is the expensive
              choice and should be taken deliberately rather than by reflex. */}
          {dl.canResume ? (
            <View className="flex-row flex-wrap items-center gap-2">
              <PressScale
                onPress={() => dl.resume(wifiOnly)}
                className="flex-row items-center gap-2 self-start rounded-full bg-primary px-5 py-2.5">
                <Icon as={Download} size={16} color={BRAND.navy} />
                <Text className="text-sm font-semibold text-primary-foreground">
                  Resume ({Math.round(dl.progress * 100)}%)
                </Text>
              </PressScale>
              <PressScale
                onPress={() => dl.start(wifiOnly)}
                className="self-start rounded-full px-4 py-2.5">
                <Text className="text-sm font-medium text-muted-foreground">Start over</Text>
              </PressScale>
            </View>
          ) : (
            <PressScale
              onPress={() => dl.start(wifiOnly)}
              className="self-start rounded-full bg-primary px-5 py-2.5">
              <Text className="text-sm font-semibold text-primary-foreground">Retry</Text>
            </PressScale>
          )}
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
