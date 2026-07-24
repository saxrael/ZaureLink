"use client";

import { AnimatePresence, motion } from "motion/react";
import { useEffect, useState } from "react";

const PAIRS: [string, string][] = [
  ["Nawa ne wannan?", "How much is this?"],
  ["Ina jin zazzabi.", "I'm feeling feverish."],
  ["Farashi na karshe.", "That's my final price."],
  ["Ka je ina?", "Where are you headed?"],
  ["Na gode sosai.", "Thank you very much."],
];

export function TranslatingChips() {
  const [index, setIndex] = useState(0);

  useEffect(() => {
    const id = setInterval(() => setIndex((i) => (i + 1) % PAIRS.length), 3000);
    return () => clearInterval(id);
  }, []);

  const [hausa, english] = PAIRS[index];

  return (
    <div className="pointer-events-none absolute inset-0 hidden items-center justify-between px-2 sm:flex md:px-6">
      <AnimatePresence mode="wait">
        <motion.div
          key={`ha-${index}`}
          initial={{ opacity: 0, x: -16, y: 10 }}
          animate={{ opacity: 1, x: 0, y: 0 }}
          exit={{ opacity: 0, x: -16, y: -10 }}
          transition={{ duration: 0.5, ease: "easeOut" }}
          className="max-w-[9.5rem] rounded-2xl rounded-bl-sm border border-white/10 bg-white/8 px-3.5 py-2.5 text-xs font-medium text-white/90 backdrop-blur-md sm:max-w-[11rem] sm:text-sm"
        >
          {hausa}
        </motion.div>
      </AnimatePresence>

      <AnimatePresence mode="wait">
        <motion.div
          key={`en-${index}`}
          initial={{ opacity: 0, x: 16, y: -10 }}
          animate={{ opacity: 1, x: 0, y: 0 }}
          exit={{ opacity: 0, x: 16, y: 10 }}
          transition={{ duration: 0.5, ease: "easeOut", delay: 0.15 }}
          className="max-w-[9.5rem] rounded-2xl rounded-br-sm border border-orange-400/30 bg-orange-500/15 px-3.5 py-2.5 text-xs font-medium text-orange-100 backdrop-blur-md sm:max-w-[11rem] sm:text-sm"
        >
          {english}
        </motion.div>
      </AnimatePresence>
    </div>
  );
}
