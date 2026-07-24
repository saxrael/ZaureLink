import '@/global.css';

import { CustomSplash } from '@/components/CustomSplash';
import { NAV_THEME } from '@/lib/theme';
import { ThemeProvider } from 'expo-router/react-navigation';
import { PortalHost } from '@rn-primitives/portal';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import { colorScheme } from 'nativewind';
import * as React from 'react';

export {
  // Catch any errors thrown by the Layout component.
  ErrorBoundary,
} from 'expo-router';

// Keep the native splash up until we explicitly hide it below, so the custom splash (already
// rendered underneath, white bg + logo) is what appears the instant the native one goes away.
SplashScreen.preventAutoHideAsync().catch(() => {});

// The brand identity is a fixed white/navy/orange look, not a light/dark pair — force NativeWind's
// scheme so it never follows the device's system dark mode (which otherwise flips the background
// dark and desaturates the brand navy against it).
colorScheme.set('light');

export default function RootLayout() {
  const [showCustomSplash, setShowCustomSplash] = React.useState(true);

  React.useEffect(() => {
    // JS content (including CustomSplash) is already mounted below by this point, so hiding the
    // native splash here reveals the custom one seamlessly — "after the default splash screen."
    SplashScreen.hideAsync().catch(() => {});
  }, []);

  return (
    <ThemeProvider value={NAV_THEME.light}>
      <StatusBar style="dark" />
      <Stack />
      <PortalHost />
      {showCustomSplash ? <CustomSplash onFinish={() => setShowCustomSplash(false)} /> : null}
    </ThemeProvider>
  );
}
