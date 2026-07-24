import { BRAND } from '@/lib/brand';
import * as React from 'react';
import { View } from 'react-native';
import Animated, {
  Easing,
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  type SharedValue,
} from 'react-native-reanimated';

// Voice-note-style visualizer. Bars react to live mic amplitude while listening and self-flow
// while translating, so the app "feels" alive to the voice's tempo/vibration. Driven entirely on
// the UI thread (Reanimated shared values) — the JS side just writes the smoothed level. Sits as a
// slim secondary detail beneath the MicOrb hero, not the primary focal point.

const BARS = 9;
const MAX_H = 26;
const MIN_H = 4;

export type WaveMode = 'idle' | 'listening' | 'processing';

type Props = {
  /** 0..1 current mic amplitude, smoothed by the caller (withTiming). */
  level: SharedValue<number>;
  mode: WaveMode;
  color?: string;
};

export function VoiceWave({ level, mode, color = BRAND.orange }: Props) {
  const flow = useSharedValue(0);

  React.useEffect(() => {
    // Continuous 0..1 sweep that drives each bar's sine phase for a flowing wave.
    flow.value = withRepeat(withTiming(1, { duration: 1500, easing: Easing.linear }), -1, false);
  }, [flow]);

  const m = mode === 'processing' ? 2 : mode === 'listening' ? 1 : 0;

  return (
    <View
      style={{
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        height: MAX_H,
        gap: 5,
      }}>
      {Array.from({ length: BARS }).map((_, i) => (
        <Bar key={i} index={i} level={level} flow={flow} m={m} color={color} />
      ))}
    </View>
  );
}

function Bar({
  index,
  level,
  flow,
  m,
  color,
}: {
  index: number;
  level: SharedValue<number>;
  flow: SharedValue<number>;
  m: number;
  color: string;
}) {
  const style = useAnimatedStyle(() => {
    const phase = index * 0.9;
    const wave = (Math.sin(flow.value * 2 * Math.PI + phase) + 1) / 2; // 0..1
    const center = (BARS - 1) / 2;
    const centerWeight = 1 - (Math.abs(index - center) / center) * 0.35; // taller in the middle
    let amp: number;
    if (m === 2) {
      amp = (0.35 + 0.45 * wave) * centerWeight; // processing: lively self-flow
    } else if (m === 1) {
      amp = (0.12 + level.value * (0.55 + 0.45 * wave)) * centerWeight; // listening: react to voice
    } else {
      amp = (0.1 + 0.05 * wave) * centerWeight; // idle: gentle breathing
    }
    return { height: MIN_H + amp * (MAX_H - MIN_H) };
  }, [m]);

  return <Animated.View style={[{ width: 5, borderRadius: 3, backgroundColor: color }, style]} />;
}
