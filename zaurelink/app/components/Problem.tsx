import { CloudOff, Lock, MessageSquareX } from "lucide-react";
import { Container } from "./Container";
import { Reveal } from "./Reveal";

const CARDS = [
  {
    icon: CloudOff,
    title: "Cloud apps need signal",
    body: "Google Translate and friends fail the instant the bars drop — in a market stall, on a Keke, or in a clinic queue where connectivity is unreliable, expensive, or just absent.",
  },
  {
    icon: MessageSquareX,
    title: "Generic MT misses local meaning",
    body: `"Farashi na karshe" doesn't mean "my last price" — it means "final price." Generic models translate words. ZaureLink is built to resolve currency shorthand, bargaining idiom, and clinical phrasing.`,
  },
  {
    icon: Lock,
    title: "Sensitive words shouldn't leave the phone",
    body: "Describing a symptom to a chemist is private. A cloud round-trip means that description touches a server you don't control. ZaureLink never sends it anywhere.",
  },
] as const;

export function Problem() {
  return (
    <section className="bg-white py-24">
      <Container>
        <Reveal className="mx-auto max-w-2xl text-center">
          <h2 className="text-balance text-3xl font-extrabold tracking-tight text-navy-900 sm:text-4xl">
            Translation apps weren&apos;t built for this moment.
          </h2>
          <p className="mt-4 text-lg text-navy-800/60">
            Students at ABU Zaria cross the Hausa↔English boundary constantly —
            in noisy markets, on transport, in healthcare settings. The tools
            that exist assume a connection you don&apos;t have.
          </p>
        </Reveal>

        <div className="mt-16 grid gap-6 md:grid-cols-3">
          {CARDS.map((card, i) => (
            <Reveal key={card.title} delay={i * 0.1}>
              <div className="group h-full rounded-3xl border border-navy-900/8 bg-navy-50/60 p-7 transition-all duration-300 hover:-translate-y-1.5 hover:border-orange-500/25 hover:bg-white hover:shadow-[0_20px_50px_-20px_rgba(31,53,95,0.35)]">
                <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-navy-900 transition-colors duration-300 group-hover:bg-orange-500">
                  <card.icon
                    className="h-5 w-5 text-orange-400 transition-colors duration-300 group-hover:text-navy-900"
                    strokeWidth={2}
                  />
                </div>
                <h3 className="mt-5 text-lg font-bold text-navy-900">
                  {card.title}
                </h3>
                <p className="mt-2 text-sm leading-relaxed text-navy-800/60">
                  {card.body}
                </p>
              </div>
            </Reveal>
          ))}
        </div>

        <Reveal delay={0.3} className="mx-auto mt-14 max-w-2xl">
          <div className="flex flex-col items-center gap-2 rounded-2xl bg-navy-900 px-8 py-6 text-center sm:flex-row sm:justify-center sm:gap-4 sm:text-left">
            <span className="text-3xl font-extrabold text-orange-400">
              50M+
            </span>
            <span className="text-sm text-white/60">
              Hausa speakers across West Africa navigate this language
              boundary daily — most without a translator that works where
              they actually are.
            </span>
          </div>
        </Reveal>
      </Container>
    </section>
  );
}
