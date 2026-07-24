"use client";

import { motion } from "motion/react";
import { Radio } from "lucide-react";
import { Button } from "./Button";
import { Container } from "./Container";
import { GradientMesh } from "./GradientMesh";
import { VoiceOrb } from "./VoiceOrb";
import { TranslatingChips } from "./TranslatingChips";

const STATS = [
  ["100%", "Offline"],
  ["<1.5s", "Response"],
  ["2", "Languages, both ways"],
  ["0", "Servers involved"],
] as const;

export function Hero() {
  return (
    <section
      id="top"
      className="relative flex min-h-screen flex-col justify-center overflow-hidden pt-28 pb-16"
    >
      <GradientMesh />

      <Container className="relative flex flex-col items-center text-center">
        <motion.div
          initial={{ opacity: 0, y: 12 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.6 }}
          className="mb-7 inline-flex items-center gap-2 rounded-full border border-orange-400/30 bg-orange-500/10 px-4 py-1.5 text-xs font-semibold tracking-wide text-orange-300 uppercase"
        >
          <Radio className="h-3.5 w-3.5" />
          Built With Gemma · GDG on Campus ABU Zaria
        </motion.div>

        <motion.h1
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.05 }}
          className="max-w-4xl text-balance text-5xl leading-[1.05] font-extrabold tracking-tight text-white sm:text-6xl md:text-7xl"
        >
          Hausa and English,
          <br />
          <span className="text-orange-400">translated live.</span> Zero signal
          required.
        </motion.h1>

        <motion.p
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.15 }}
          className="mt-6 max-w-xl text-balance text-lg text-white/60"
        >
          ZaureLink runs an entire speech-to-speech translator on your phone —
          Gemma 4 E2B, fully on-device. No cloud round-trip, no data plan, no
          exposure of what you say. Built for markets, campuses, and clinics
          where connectivity isn&apos;t guaranteed.
        </motion.p>

        <motion.div
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.7, delay: 0.25 }}
          className="mt-9 flex flex-col items-center gap-4 sm:flex-row"
        >
          <Button href="#download" size="lg" variant="primary">
            Download for Android
          </Button>
          <Button href="#how-it-works" size="lg" variant="ghost-dark">
            See how it works
          </Button>
        </motion.div>

        {/* Centerpiece: the reactive voice orb + live-translating chips */}
        <motion.div
          initial={{ opacity: 0, scale: 0.9 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.8, delay: 0.35, ease: [0.16, 1, 0.3, 1] }}
          className="relative mt-16 flex items-center justify-center"
        >
          <div className="relative">
            <VoiceOrb size={168} />
            <TranslatingChips />
          </div>
        </motion.div>

        <motion.div
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          transition={{ duration: 0.7, delay: 0.5 }}
          className="mt-16 grid w-full max-w-2xl grid-cols-2 gap-6 border-t border-white/10 pt-8 sm:grid-cols-4"
        >
          {STATS.map(([value, label]) => (
            <div key={label} className="flex flex-col items-center gap-1">
              <span className="text-2xl font-extrabold text-white">
                {value}
              </span>
              <span className="text-xs text-white/45">{label}</span>
            </div>
          ))}
        </motion.div>
      </Container>
    </section>
  );
}
