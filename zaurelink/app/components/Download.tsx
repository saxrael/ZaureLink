import { CheckCircle2, Download as DownloadIcon, Smartphone } from "lucide-react";
import { Button } from "./Button";
import { Container } from "./Container";
import { GradientMesh } from "./GradientMesh";
import { Reveal } from "./Reveal";

// Update these once the production APK is in place at public/downloads/zaurelink.apk.
const APK_PATH = "/downloads/zaurelink.apk";
const APK_VERSION = "v1.0";
const APK_SIZE = "~50MB";

const REQUIREMENTS = [
  "Android 7.0 (API 24) or newer",
  "~50MB initial install, downloaded once",
  "~2.6GB one-time offline model download (Wi-Fi recommended)",
  "100% functional in airplane mode after setup",
];

export function Download() {
  return (
    <section id="download" className="relative overflow-hidden bg-navy-950 py-28">
      <GradientMesh className="opacity-70" />
      <Container className="relative">
        <Reveal className="mx-auto max-w-xl text-center">
          <span className="inline-flex items-center gap-2 rounded-full border border-orange-400/30 bg-orange-500/10 px-4 py-1.5 text-xs font-semibold tracking-wide text-orange-300 uppercase">
            <Smartphone className="h-3.5 w-3.5" />
            Available now
          </span>
          <h2 className="mt-5 text-balance text-3xl font-extrabold tracking-tight text-white sm:text-4xl">
            Get ZaureLink on your phone.
          </h2>
          <p className="mt-4 text-lg text-white/55">
            Direct Android install — no Play Store needed yet. Open this page
            on your phone to download straight to it.
          </p>
        </Reveal>

        <Reveal delay={0.1} className="mx-auto mt-12 max-w-lg">
          <div className="rounded-3xl border border-white/10 bg-white/5 p-8 backdrop-blur-lg">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="flex h-12 w-12 items-center justify-center rounded-2xl bg-orange-500">
                  <Smartphone className="h-6 w-6 text-navy-900" strokeWidth={2.25} />
                </div>
                <div>
                  <p className="text-sm font-bold text-white">
                    ZaureLink for Android
                  </p>
                  <p className="text-xs text-white/45">
                    {APK_VERSION} · {APK_SIZE} · .apk
                  </p>
                </div>
              </div>
            </div>

            <Button
              href={APK_PATH}
              download="zaurelink.apk"
              size="lg"
              variant="primary"
              className="mt-6 w-full"
            >
              <DownloadIcon className="h-4.5 w-4.5" />
              Download APK
            </Button>

            <ul className="mt-7 flex flex-col gap-3">
              {REQUIREMENTS.map((req) => (
                <li
                  key={req}
                  className="flex items-start gap-2.5 text-sm text-white/60"
                >
                  <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-orange-400" />
                  {req}
                </li>
              ))}
            </ul>
          </div>
        </Reveal>

        <Reveal delay={0.2} className="mt-6 text-center text-xs text-white/35">
          Android may warn about installing from outside the Play Store —
          that&apos;s expected for direct APK installs. Play Store listing:
          coming soon.
        </Reveal>
      </Container>
    </section>
  );
}
