import { ArrowRight, Cpu, Mic2, ShieldAlert, Volume2 } from "lucide-react";
import { Container } from "./Container";
import { Reveal } from "./Reveal";

const PIPELINE = [
  {
    icon: Mic2,
    title: "Capture",
    note: "16kHz mic input, dual-channel privacy routing",
  },
  {
    icon: ShieldAlert,
    title: "Clean & gate",
    note: "WebRTC noise suppression + Silero VAD",
  },
  {
    icon: Cpu,
    title: "Gemma 4 E2B",
    note: "On-device inference via LiteRT-LM",
  },
  {
    icon: Volume2,
    title: "Speak",
    note: "On-device Hausa & English voice synthesis",
  },
] as const;

const BADGES = [
  "Gemma 4 E2B",
  "LiteRT-LM",
  "Silero VAD",
  "WebRTC Noise Suppression",
  "ONNX Runtime",
  "Meta MMS Hausa Voice",
];

export function TechShowcase() {
  return (
    <section id="tech" className="relative overflow-hidden bg-navy-950 py-24">
      <div
        className="pointer-events-none absolute inset-0 opacity-50"
        style={{
          background:
            "radial-gradient(ellipse 80% 50% at 50% 0%, rgba(250,153,35,0.12), transparent)",
        }}
      />
      <Container className="relative">
        <Reveal className="mx-auto max-w-2xl text-center">
          <span className="text-sm font-semibold tracking-wide text-orange-400 uppercase">
            Under the hood
          </span>
          <h2 className="mt-3 text-balance text-3xl font-extrabold tracking-tight text-white sm:text-4xl">
            Real AI, running entirely in your pocket.
          </h2>
          <p className="mt-4 text-lg text-white/55">
            No API calls. No round-trip. Google&apos;s Gemma 4 E2B loads
            straight from disk via LiteRT-LM and runs the whole conversation
            on your phone&apos;s own CPU — the same architecture, whether
            you&apos;re in airplane mode or not.
          </p>
        </Reveal>

        <div className="mt-16 flex flex-col items-stretch gap-3 md:flex-row md:items-center md:justify-center md:gap-2">
          {PIPELINE.map((stage, i) => (
            <div key={stage.title} className="flex items-center gap-2 md:contents">
              <Reveal delay={i * 0.1} className="flex-1">
                <div className="flex h-full flex-col items-center gap-3 rounded-2xl border border-white/10 bg-white/5 p-6 text-center backdrop-blur">
                  <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-orange-500/15">
                    <stage.icon className="h-5 w-5 text-orange-400" strokeWidth={2} />
                  </div>
                  <div>
                    <h3 className="text-sm font-bold text-white">
                      {stage.title}
                    </h3>
                    <p className="mt-1 text-xs text-white/45">{stage.note}</p>
                  </div>
                </div>
              </Reveal>
              {i < PIPELINE.length - 1 ? (
                <ArrowRight className="hidden h-5 w-5 shrink-0 text-white/20 md:block" />
              ) : null}
            </div>
          ))}
        </div>

        <Reveal delay={0.3} className="mt-14 flex flex-wrap items-center justify-center gap-3">
          {BADGES.map((b) => (
            <span
              key={b}
              className="rounded-full border border-white/10 bg-white/5 px-4 py-1.5 text-xs font-medium text-white/60"
            >
              {b}
            </span>
          ))}
        </Reveal>
      </Container>
    </section>
  );
}
