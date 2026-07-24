import { Icon } from '@/components/ui/icon';
import { PressScale } from '@/components/PressScale';
import { BRAND } from '@/lib/brand';
import { Mic, MicOff } from 'lucide-react-native';
import * as React from 'react';
import { View, type GestureResponderEvent } from 'react-native';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  withRepeat,
  withTiming,
  type SharedValue,
} from 'react-native-reanimated';
import Svg, { Circle, Defs, RadialGradient, Stop } from 'react-native-svg';

const SIZE = 104;
const GLOW_SIZE = SIZE * 2.3;

export type OrbMode = 'idle' | 'listening' | 'processing' | 'disabled';

// The hero control: a glowing orb that breathes with the mic's live amplitude while listening, and
// pulses on its own while translating — the "it's alive" detail a flat button can't give you. The
// glow is an SVG radial gradient (react-native-svg, already installed — no new native dependency),
// animated by scaling/fading its wrapper on the UI thread via Reanimated.
export function MicOrb({
  mode,
  level,
  onPressIn,
  onPressOut,
  onPress,
}: {
  mode: OrbMode;
  level: SharedValue<number>;
  onPressIn?: (e: GestureResponderEvent) => void;
  onPressOut?: (e: GestureResponderEvent) => void;
  onPress?: (e: GestureResponderEvent) => void;
}) {
  const pulse = useSharedValue(0);

  React.useEffect(() => {
    if (mode === 'processing') {
      pulse.value = withRepeat(withTiming(1, { duration: 900 }), -1, true);
    } else {
      pulse.value = withTiming(0, { duration: 250 });
    }
  }, [mode, pulse]);

  const orbStyle = useAnimatedStyle(() => {
    const reactive = mode === 'listening' ? level.value : 0;
    const scale = 1 + reactive * 0.18 + pulse.value * 0.06;
    return { transform: [{ scale }] };
  });

  const glowStyle = useAnimatedStyle(() => {
    const reactive = mode === 'listening' ? level.value : 0;
    const raw = Math.max(reactive, pulse.value);
    return {
      opacity: 0.35 + raw * 0.65,
      transform: [{ scale: 1 + reactive * 0.35 + pulse.value * 0.25 }],
    };
  });

  return (
    <View style={{ width: GLOW_SIZE, height: GLOW_SIZE, alignItems: 'center', justifyContent: 'center' }}>
      <Animated.View
        pointerEvents="none"
        style={[{ position: 'absolute', width: GLOW_SIZE, height: GLOW_SIZE }, glowStyle]}>
        <Svg width={GLOW_SIZE} height={GLOW_SIZE}>
          <Defs>
            <RadialGradient id="orbGlow" cx="50%" cy="50%" r="50%">
              <Stop offset="0%" stopColor={BRAND.orange} stopOpacity={0.55} />
              <Stop offset="55%" stopColor={BRAND.orange} stopOpacity={0.16} />
              <Stop offset="100%" stopColor={BRAND.orange} stopOpacity={0} />
            </RadialGradient>
          </Defs>
          <Circle cx={GLOW_SIZE / 2} cy={GLOW_SIZE / 2} r={GLOW_SIZE / 2} fill="url(#orbGlow)" />
        </Svg>
      </Animated.View>

      <PressScale
        scaleTo={0.92}
        onPressIn={onPressIn}
        onPressOut={onPressOut}
        onPress={onPress}
        disabled={mode === 'disabled'}
        style={{ width: SIZE, height: SIZE, borderRadius: SIZE / 2 }}>
        <Animated.View
          style={[
            {
              width: SIZE,
              height: SIZE,
              borderRadius: SIZE / 2,
              alignItems: 'center',
              justifyContent: 'center',
              backgroundColor: BRAND.orange,
              shadowColor: BRAND.navy,
              shadowOpacity: 0.25,
              shadowRadius: 12,
              shadowOffset: { width: 0, height: 6 },
              elevation: 6,
            },
            orbStyle,
          ]}>
          <Icon as={mode === 'disabled' ? MicOff : Mic} size={38} color={BRAND.navy} />
        </Animated.View>
      </PressScale>
    </View>
  );
}
