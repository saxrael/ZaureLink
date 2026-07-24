import * as React from 'react';
import { Pressable, type PressableProps, type StyleProp, type ViewStyle } from 'react-native';

type Props = Omit<PressableProps, 'style'> & {
  scaleTo?: number;
  className?: string;
  style?: StyleProp<ViewStyle>;
  children: React.ReactNode;
};

// Tactile press feedback (subtle scale-down) via React Native's built-in Pressable state callback
// — a plain `Pressable` used directly, exactly like every other Pressable in this app, so it gets
// NativeWind's automatic className support for free. (An earlier version wrapped Pressable in
// Animated.createAnimatedComponent for a smoother eased animation, but that requires manually
// registering cssInterop, and in practice produced corrupted layouts — components losing their
// intended width/height. Plain Pressable's built-in pressed-state style is a hard trade for
// reliability: the feedback is an instant snap instead of an eased transition, but it always
// renders correctly.)
export function PressScale({
  children,
  scaleTo = 0.94,
  style,
  className,
  disabled,
  ...rest
}: Props) {
  return (
    <Pressable
      className={className}
      disabled={disabled}
      style={({ pressed }) => [
        style,
        pressed ? { transform: [{ scale: scaleTo }] } : null,
        disabled ? { opacity: 0.5 } : null,
      ]}
      {...rest}>
      {children}
    </Pressable>
  );
}
