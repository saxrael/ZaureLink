import Image from "next/image";
import Link from "next/link";

const SPECS = [
  ["Model", "Gemma 4 E2B (int4)"],
  ["Runtime", "LiteRT-LM, on-device"],
  ["Languages", "Hausa ↔ English"],
  ["Connectivity", "None required"],
] as const;

const LINKS = [
  { label: "Features", href: "#features" },
  { label: "How it works", href: "#how-it-works" },
  { label: "Tech", href: "#tech" },
  { label: "Download", href: "#download" },
] as const;

export function Footer() {
  return (
    <footer className="bg-navy-950 pt-16 pb-8">
      <div className="mx-auto w-full max-w-6xl px-6 sm:px-8">
        <div className="grid gap-12 border-b border-white/10 pb-12 sm:grid-cols-2 lg:grid-cols-3">
          <div>
            <Link href="#top" className="flex items-center gap-2.5">
              <Image
                src="/zaurelink-icon.png"
                alt=""
                width={32}
                height={32}
                className="rounded-lg"
              />
              <span className="text-lg font-extrabold tracking-tight text-white">
                ZaureLink
              </span>
            </Link>
            <p className="mt-4 max-w-xs text-sm leading-relaxed text-white/45">
              Offline speech-to-speech translation for Hausa and English —
              built for markets, campuses, and everywhere the signal doesn&apos;t
              reach.
            </p>
          </div>

          <div>
            <p className="text-xs font-semibold tracking-wide text-white/40 uppercase">
              Explore
            </p>
            <ul className="mt-4 flex flex-col gap-3">
              {LINKS.map((l) => (
                <li key={l.href}>
                  <Link
                    href={l.href}
                    className="text-sm text-white/60 transition hover:text-orange-400"
                  >
                    {l.label}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          <div>
            <p className="text-xs font-semibold tracking-wide text-white/40 uppercase">
              Under the hood
            </p>
            <dl className="mt-4 flex flex-col gap-3">
              {SPECS.map(([k, v]) => (
                <div key={k} className="flex items-baseline justify-between gap-4 text-sm">
                  <dt className="text-white/40">{k}</dt>
                  <dd className="font-medium text-white/70">{v}</dd>
                </div>
              ))}
            </dl>
          </div>
        </div>

        <div className="flex flex-col-reverse items-center gap-4 pt-8 sm:flex-row sm:justify-between">
          <p className="text-xs text-white/35">
            © {new Date().getFullYear()} ZaureLink. Built for the Build With
            Gemma hackathon.
          </p>
          <p className="text-xs text-white/35">
            GDG on Campus · Ahmadu Bello University, Zaria
          </p>
        </div>
      </div>
    </footer>
  );
}
