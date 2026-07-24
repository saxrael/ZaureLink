"use client";

import { motion } from "motion/react";
import { Mic } from "lucide-react";

const BAR_COUNT = 9;

export function VoiceOrb({ size = 176 }: { size?: number }) {
  const glow = size * 2.4;

  return (
    <div
      className="relative flex items-center justify-center"
      style={{ width: glow, height: glow }}
    >
      {/* Outer breathing glow */}
      <motion.div
        className="absolute rounded-full blur-3xl"
        style={{
          width: glow,
          height: glow,
          background:
            "radial-gradient(circle, rgba(250,153,35,0.55) 0%, rgba(250,153,35,0.15) 45%, transparent 70%)",
        }}
        animate={{ scale: [1, 1.15, 1], opacity: [0.6, 1, 0.6] }}
        transition={{ duration: 3.2, repeat: Infinity, ease: "easeInOut" }}
      />

      {/* Waveform ring */}
      <div className="absolute flex items-end gap-[6px]" style={{ bottom: glow * 0.06 }}>
        {Array.from({ length: BAR_COUNT }).map((_, i) => (
          <motion.span
            key={i}
            className="w-[5px] rounded-full bg-orange-300/70"
            style={{ height: 10 }}
            animate={{ height: [10, 10 + ((i % 5) + 1) * 9, 10] }}
            transition={{
              duration: 1.1 + (i % 3) * 0.2,
              repeat: Infinity,
              ease: "easeInOut",
              delay: i * 0.08,
            }}
          />
        ))}
      </div>

      {/* Solid orb */}
      <motion.div
        className="relative flex items-center justify-center rounded-full"
        style={{
          width: size,
          height: size,
          background:
            "radial-gradient(circle at 32% 28%, var(--color-orange-300), var(--color-orange-500) 55%, var(--color-orange-600) 100%)",
          boxShadow:
            "0 20px 60px -12px rgba(250,153,35,0.55), inset 0 -8px 20px rgba(15,27,48,0.25)",
        }}
        animate={{ scale: [1, 1.045, 1] }}
        transition={{ duration: 3.2, repeat: Infinity, ease: "easeInOut" }}
      >
        <Mic
          className="text-navy-900"
          style={{ width: size * 0.36, height: size * 0.36 }}
          strokeWidth={2.25}
        />
      </motion.div>
    </div>
  );
}
