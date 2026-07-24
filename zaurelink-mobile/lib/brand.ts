// Exact brand hex values, for contexts that need raw color strings rather than Tailwind classes
// (SVG gradients, Reanimated interpolated styles). Mirrors tailwind.config.js `brand.*` and the
// --primary/--foreground HSL tokens in global.css — keep all three in sync if these ever change.
export const BRAND = {
  navy: '#1f355f',
  orange: '#fa9923',
  white: '#ffffff',
} as const;
