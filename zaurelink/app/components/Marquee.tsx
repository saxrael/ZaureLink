const ITEMS = [
  "Market bargaining",
  "Campus admissions",
  "Clinic visits",
  "Transport fares",
  "NYSC documentation",
  "Currency shorthand",
  "Mid-sentence code-switching",
  "Hausa ↔ English, both ways",
] as const;

function Track() {
  return (
    <div className="flex shrink-0 items-center gap-10 pr-10">
      {ITEMS.map((item) => (
        <span key={item} className="flex items-center gap-10 whitespace-nowrap">
          <span className="text-sm font-semibold tracking-wide text-navy-900/70 uppercase">
            {item}
          </span>
          <span className="h-1.5 w-1.5 rounded-full bg-orange-500" />
        </span>
      ))}
    </div>
  );
}

export function Marquee() {
  return (
    <div className="overflow-hidden border-y border-navy-900/8 bg-navy-50/60 py-6">
      <div className="flex w-max animate-marquee">
        <Track />
        <Track />
      </div>
    </div>
  );
}
