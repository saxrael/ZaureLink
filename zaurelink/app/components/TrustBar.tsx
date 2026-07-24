import { Reveal } from "./Reveal";

const PARTNERS = [
  "Built With Gemma Hackathon",
  "In partnership with Google DeepMind — Gemma Team",
  "GDG on Campus · ABU Zaria",
] as const;

export function TrustBar() {
  return (
    <div className="relative border-y border-white/10 bg-navy-950 py-5">
      <Reveal className="mx-auto flex w-full max-w-6xl flex-wrap items-center justify-center gap-x-10 gap-y-3 px-6 sm:px-8">
        {PARTNERS.map((p, i) => (
          <span key={p} className="flex items-center gap-2">
            {i > 0 ? (
              <span className="hidden h-1 w-1 rounded-full bg-white/25 sm:block" />
            ) : null}
            <span className="text-xs font-semibold tracking-wide text-white/50 uppercase">
              {p}
            </span>
          </span>
        ))}
      </Reveal>
    </div>
  );
}
