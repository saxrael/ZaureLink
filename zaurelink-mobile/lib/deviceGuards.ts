import * as Battery from 'expo-battery';
import { Platform } from 'react-native';

// FR-08 low-battery guard: warn before a long download or an inference session when the device is
// low and not charging, to avoid LowMemoryKiller / mid-task death on the target hardware floor.

const LOW_BATTERY = 0.15;

export type BatteryGuard = { ok: boolean; level: number; charging: boolean; message: string | null };

export async function checkBattery(): Promise<BatteryGuard> {
  if (Platform.OS === 'web') {
    return { ok: true, level: 1, charging: true, message: null };
  }
  try {
    const level = await Battery.getBatteryLevelAsync(); // 0..1 (-1 if unknown)
    const state = await Battery.getBatteryStateAsync();
    const charging =
      state === Battery.BatteryState.CHARGING || state === Battery.BatteryState.FULL;
    const low = level >= 0 && level < LOW_BATTERY && !charging;
    return {
      ok: !low,
      level,
      charging,
      message: low
        ? `Battery low (${Math.round(level * 100)}%). Charge above ${Math.round(
            LOW_BATTERY * 100
          )}% or plug in first.`
        : null,
    };
  } catch {
    // If battery state is unreadable, don't block — fail open for availability.
    return { ok: true, level: -1, charging: false, message: null };
  }
}
