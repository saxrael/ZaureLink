import { Text } from '@/components/ui/text';
import * as React from 'react';
import { Pressable, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withSpring } from 'react-native-reanimated';

// A pill-shaped two-option switch with a sliding highlight — the small signature interaction that
// reads as "designed" rather than two plain buttons. Evenly split 50/50: the indicator is 50% wide
// and slides between left:0% and left:50% of the (padding-excluded) content box — plain percentages
// only, since React Native's style engine has no CSS calc().
export function TwoOptionToggle({
  leftLabel,
  rightLabel,
  value,
  onChange,
}: {
  leftLabel: string;
  rightLabel: string;
  value: 'left' | 'right';
  onChange: (value: 'left' | 'right') => void;
}) {
  const progress = useSharedValue(value === 'right' ? 1 : 0);

  React.useEffect(() => {
    progress.value = withSpring(value === 'right' ? 1 : 0, { damping: 18, stiffness: 180 });
  }, [value, progress]);

  const indicatorStyle = useAnimatedStyle(() => ({
    left: `${progress.value * 50}%`,
  }));

  return (
    <View className="flex-row rounded-full bg-muted p-1">
      <Animated.View className="absolute inset-y-1 w-1/2 rounded-full bg-primary shadow-sm" style={indicatorStyle} />
      <Pressable className="flex-1 items-center py-2.5" onPress={() => onChange('left')}>
        <Text
          className={`text-sm font-semibold ${value === 'left' ? 'text-primary-foreground' : 'text-muted-foreground'}`}>
          {leftLabel}
        </Text>
      </Pressable>
      <Pressable className="flex-1 items-center py-2.5" onPress={() => onChange('right')}>
        <Text
          className={`text-sm font-semibold ${value === 'right' ? 'text-primary-foreground' : 'text-muted-foreground'}`}>
          {rightLabel}
        </Text>
      </Pressable>
    </View>
  );
}
