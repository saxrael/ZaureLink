import {
  Languages,
  MapPinned,
  Repeat,
  ShieldCheck,
  Sun,
  Brain,
} from "lucide-react";
import { Container } from "./Container";
import { Reveal } from "./Reveal";
import { PhoneMockup } from "./PhoneMockup";

const FEATURES = [
  {
    icon: MapPinned,
    title: "Market & Campus modes",
    body: "Two domain-tuned configurations — bargaining shorthand and currency slang in Market mode, transport fares and clinical phrasing in Campus mode.",
  },
  {
    icon: Languages,
    title: "Truly bidirectional",
    body: "Either person can be the Hausa speaker or the English speaker. You choose your own language once — ZaureLink figures out the rest, every turn.",
  },
  {
    icon: Repeat,
    title: "Never over-translates",
    body: "If someone already spoke the language their listener needs, ZaureLink relays it unchanged — even mid-sentence code-switching is handled as one unit.",
  },
  {
    icon: ShieldCheck,
    title: "Privacy-first by hardware",
    body: "Your earpiece is your private channel. A Bluetooth disconnect fails closed — mute and a visible prompt — never a silent leak to the public speaker.",
  },
  {
    icon: Brain,
    title: "Remembers the conversation",
    body: `Resolves "it" to the price just quoted, keeps a multi-sentence symptom description coherent, and matches each speaker's tone across turns.`,
  },
  {
    icon: Sun,
    title: "Built for outdoor light",
    body: "High-contrast, large-type transcript that stays legible in direct Nigerian midday sun — because the market is where this app actually lives.",
  },
] as const;

export function Features() {
  return (
    <section id="features" className="bg-navy-50/50 py-24">
      <Container>
        <Reveal className="mx-auto max-w-2xl text-center">
          <span className="text-sm font-semibold tracking-wide text-orange-600 uppercase">
            Features
          </span>
          <h2 className="mt-3 text-balance text-3xl font-extrabold tracking-tight text-navy-900 sm:text-4xl">
            Everything a real conversation needs.
          </h2>
        </Reveal>

        <div className="mt-16 grid items-start gap-12 lg:grid-cols-2 lg:gap-16">
          <div className="lg:sticky lg:top-28">
            <PhoneMockup />
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            {FEATURES.map((f, i) => (
              <Reveal key={f.title} delay={(i % 2) * 0.08}>
                <div className="group h-full rounded-2xl border border-navy-900/8 bg-white p-6 shadow-[0_1px_2px_rgba(15,27,48,0.04)] transition-all duration-300 hover:-translate-y-1 hover:border-orange-500/20 hover:shadow-[0_16px_40px_-18px_rgba(31,53,95,0.3)]">
                  <div className="flex h-9 w-9 items-center justify-center rounded-xl bg-orange-50 transition-colors duration-300 group-hover:bg-orange-500">
                    <f.icon
                      className="h-4.5 w-4.5 text-orange-600 transition-colors duration-300 group-hover:text-white"
                      strokeWidth={2}
                    />
                  </div>
                  <h3 className="mt-4 text-base font-bold text-navy-900">
                    {f.title}
                  </h3>
                  <p className="mt-1.5 text-sm leading-relaxed text-navy-800/60">
                    {f.body}
                  </p>
                </div>
              </Reveal>
            ))}
          </div>
        </div>
      </Container>
    </section>
  );
}
