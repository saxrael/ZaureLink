import * as React from 'react';
import { Image, Platform, StyleSheet } from 'react-native';
import Animated, {
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withTiming,
} from 'react-native-reanimated';

const LOGO = require('@/assets/images/zaurelink-logo.png');

const DISPLAY_MS = 3000;
const FADE_MS = 300;

// Shown right after the native (expo-splash-screen) splash hides, on a white background, for a
// fixed hold before fading into the real app. Purely cosmetic — no app state depends on it.
export function CustomSplash({ onFinish }: { onFinish: () => void }) {
  const opacity = useSharedValue(1);

  React.useEffect(() => {
    const timer = setTimeout(() => {
      opacity.value = withTiming(0, { duration: FADE_MS }, (finished) => {
        if (finished) runOnJS(onFinish)();
      });
    }, DISPLAY_MS);
    return () => clearTimeout(timer);
  }, [onFinish, opacity]);

  const animatedStyle = useAnimatedStyle(() => ({ opacity: opacity.value }));

  return (
    <Animated.View style={[StyleSheet.absoluteFill, styles.container, animatedStyle]}>
      <Image source={LOGO} style={styles.logo} resizeMode="contain" />
    </Animated.View>
  );
}

const styles = StyleSheet.create({
  container: {
    backgroundColor: '#ffffff',
    alignItems: 'center',
    justifyContent: 'center',
    zIndex: 999,
    ...Platform.select({ android: { elevation: 999 } }),
  },
  logo: {
    width: 240,
    height: 240,
  },
});
