"use client";

import { motion } from "motion/react";
import Image from "next/image";
import { Mic, Volume2 } from "lucide-react";

function ToggleRow({ left, right }: { left: string; right: string }) {
  return (
    <div className="flex rounded-full bg-navy-50 p-1">
      <div className="flex-1 rounded-full bg-orange-500 py-2 text-center text-[11px] font-semibold text-navy-900">
        {left}
      </div>
      <div className="flex-1 py-2 text-center text-[11px] font-semibold text-navy-800/40">
        {right}
      </div>
    </div>
  );
}

export function PhoneMockup() {
  return (
    <motion.div
      initial={{ opacity: 0, y: 40, rotate: -2 }}
      whileInView={{ opacity: 1, y: 0, rotate: 0 }}
      viewport={{ once: true, margin: "-100px" }}
      transition={{ duration: 0.8, ease: [0.16, 1, 0.3, 1] }}
      className="relative mx-auto w-[280px] select-none sm:w-[310px]"
    >
      {/* Phone bezel */}
      <div className="relative rounded-[2.75rem] border-[6px] border-navy-950 bg-navy-950 p-2 shadow-[0_40px_80px_-20px_rgba(15,27,48,0.5)]">
        <div className="absolute top-2 left-1/2 z-10 h-5 w-24 -translate-x-1/2 rounded-full bg-navy-950" />
        <div className="overflow-hidden rounded-[2.1rem] bg-white">
          {/* Status bar */}
          <div className="flex items-center justify-between px-5 pt-3 pb-1 text-[10px] font-medium text-navy-900/70">
            <span>9:41</span>
            <span>●●●</span>
          </div>

          <div className="flex flex-col gap-3 px-4 pt-1 pb-6">
            {/* Header */}
            <div className="flex items-center gap-2 pt-1">
              <Image
                src="/zaurelink-icon.png"
                alt=""
                width={26}
                height={26}
                className="rounded-[7px]"
              />
              <span className="text-sm font-extrabold tracking-tight text-navy-800">
                ZaureLink
              </span>
            </div>

            <ToggleRow left="Market" right="Campus" />
            <ToggleRow left="Hausa" right="English" />

            {/* Mini orb */}
            <div className="flex flex-col items-center gap-2 py-3">
              <div
                className="flex h-16 w-16 items-center justify-center rounded-full"
                style={{
                  background:
                    "radial-gradient(circle at 32% 28%, var(--color-orange-300), var(--color-orange-500) 55%, var(--color-orange-600) 100%)",
                  boxShadow: "0 12px 30px -8px rgba(250,153,35,0.55)",
                }}
              >
                <Mic className="h-6 w-6 text-navy-900" strokeWidth={2.25} />
              </div>
              <span className="text-[11px] font-medium text-navy-800/60">
                Hold to talk
              </span>
            </div>

            {/* Transcript cards */}
            <div className="flex flex-col gap-2">
              <div className="rounded-2xl border border-navy-900/8 bg-white p-3 shadow-[0_8px_24px_-16px_rgba(15,27,48,0.4)]">
                <div className="flex items-center justify-between">
                  <span className="text-[9px] font-medium text-navy-800/45">
                    Market · You → English
                  </span>
                  <Volume2 className="h-3 w-3 text-orange-500" />
                </div>
                <p className="mt-1 text-[11px] text-navy-800/55">Nawa ne wannan?</p>
                <p className="mt-0.5 text-sm font-bold text-navy-900">
                  How much is this?
                </p>
              </div>
              <div className="rounded-2xl border border-navy-900/8 bg-white p-3 shadow-[0_8px_24px_-16px_rgba(15,27,48,0.4)]">
                <div className="flex items-center justify-between">
                  <span className="text-[9px] font-medium text-navy-800/45">
                    Market · Other party → Hausa
                  </span>
                  <Volume2 className="h-3 w-3 text-orange-500" />
                </div>
                <p className="mt-1 text-[11px] text-navy-800/55">Two hundred naira.</p>
                <p className="mt-0.5 text-sm font-bold text-navy-900">
                  Naira dari biyu.
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </motion.div>
  );
}
