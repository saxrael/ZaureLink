import { Container } from "./Container";
import { Reveal } from "./Reveal";

const STEPS = [
  {
    title: "Choose your mode & language",
    body: "Market or Campus. Hausa or English — whichever is yours. Both are session-level choices, set once at the start.",
  },
  {
    title: "Just talk",
    body: "Hold to talk, or let auto-listen segment your speech automatically as you go back and forth.",
  },
  {
    title: "Instant translation",
    body: "Text appears on screen immediately; the spoken translation follows in under 1.5 seconds — no visible loading moment.",
  },
  {
    title: "The conversation keeps its memory",
    body: "Prices, symptoms, and tone carry across turns until you end the conversation or switch modes.",
  },
] as const;

export function HowItWorks() {
  return (
    <section id="how-it-works" className="bg-white py-24">
      <Container>
        <Reveal className="mx-auto max-w-2xl text-center">
          <span className="text-sm font-semibold tracking-wide text-orange-600 uppercase">
            How it works
          </span>
          <h2 className="mt-3 text-balance text-3xl font-extrabold tracking-tight text-navy-900 sm:text-4xl">
            From silence to spoken translation in four steps.
          </h2>
        </Reveal>

        <div className="mx-auto mt-16 max-w-3xl">
          {STEPS.map((step, i) => (
            <Reveal key={step.title} delay={i * 0.08}>
              <div className="flex gap-6 pb-10 last:pb-0">
                <div className="flex flex-col items-center">
                  <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-navy-900 text-sm font-bold text-orange-400">
                    {i + 1}
                  </span>
                  {i < STEPS.length - 1 ? (
                    <span className="mt-2 w-px flex-1 bg-navy-900/10" />
                  ) : null}
                </div>
                <div className="pb-2">
                  <h3 className="text-lg font-bold text-navy-900">
                    {step.title}
                  </h3>
                  <p className="mt-1.5 text-sm leading-relaxed text-navy-800/60">
                    {step.body}
                  </p>
                </div>
              </div>
            </Reveal>
          ))}
        </div>
      </Container>
    </section>
  );
}
