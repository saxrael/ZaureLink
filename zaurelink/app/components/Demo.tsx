import { PlayCircle, Video } from "lucide-react";
import { Container } from "./Container";
import { Reveal } from "./Reveal";
import { publicAsset } from "../lib/publicAsset";

// Drop the screen recording here and rebuild — the section switches from the waiting state to the
// player on its own, no code change. A poster frame is optional but worth having: with
// preload="none" the player is a blank rectangle until the visitor presses play.
const VIDEO_PATH = "/demo/zaurelink-demo.mp4";
const POSTER_PATH = "/demo/poster.jpg";
const CAPTIONS_PATH = "/demo/captions.vtt";

const HIGHLIGHTS = [
  "Live Hausa ↔ English speech translation",
  "Running fully offline on-device — airplane mode",
  "Gemma 4 E2B, fine-tuned for market and campus speech",
];

export function Demo() {
  const video = publicAsset(VIDEO_PATH);
  const poster = publicAsset(POSTER_PATH);
  const captions = publicAsset(CAPTIONS_PATH);

  return (
    <section id="demo" className="bg-navy-50/50 py-24">
      <Container>
        <Reveal className="mx-auto max-w-2xl text-center">
          <span className="inline-flex items-center gap-2 rounded-full border border-navy-100 bg-white px-4 py-1.5 text-xs font-semibold tracking-wide text-navy-700 uppercase">
            <Video className="h-3.5 w-3.5" />
            See it working
          </span>
          <h2 className="mt-5 text-balance text-3xl font-extrabold tracking-tight text-navy-900 sm:text-4xl">
            A real conversation, translated on-device.
          </h2>
          <p className="mt-4 text-lg text-navy-600">
            No cloud, no network. Recorded on an Android phone with the offline
            model installed.
          </p>
        </Reveal>

        <Reveal delay={0.1} className="mt-12">
          <div className="mx-auto max-w-3xl overflow-hidden rounded-3xl border border-navy-100 bg-navy-950 p-3 shadow-2xl shadow-navy-900/10">
            {video.exists ? (
              <video
                controls
                preload="none"
                playsInline
                poster={poster.exists ? POSTER_PATH : undefined}
                aria-label="ZaureLink app demonstration"
                className="mx-auto max-h-[76vh] rounded-2xl bg-black"
              >
                <source src={VIDEO_PATH} type="video/mp4" />
                {/* Captions render only once the .vtt is actually present — pointing <track> at a
                    missing file gives the visitor a caption menu that silently does nothing. */}
                {captions.exists ? (
                  <track
                    src={CAPTIONS_PATH}
                    kind="subtitles"
                    srcLang="en"
                    label="English"
                    default
                  />
                ) : null}
                Your browser can&apos;t play this video.{" "}
                <a href={VIDEO_PATH} download>
                  Download it instead
                </a>
                .
              </video>
            ) : (
              <div className="flex aspect-video flex-col items-center justify-center gap-3 rounded-2xl border border-dashed border-white/15 px-6 text-center">
                <PlayCircle className="h-10 w-10 text-orange-400" strokeWidth={1.5} />
                <p className="text-base font-semibold text-white">
                  Demo recording coming shortly
                </p>
                <p className="max-w-sm text-sm text-white/50">
                  The walkthrough is being recorded on-device. Meanwhile, the
                  APK below runs the real thing.
                </p>
              </div>
            )}
          </div>
        </Reveal>

        <Reveal delay={0.2}>
          <ul className="mx-auto mt-10 flex max-w-3xl flex-col gap-3 sm:flex-row sm:justify-center sm:gap-8">
            {HIGHLIGHTS.map((item) => (
              <li
                key={item}
                className="flex items-start gap-2 text-sm text-navy-600"
              >
                <span
                  aria-hidden
                  className="mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full bg-orange-500"
                />
                {item}
              </li>
            ))}
          </ul>
        </Reveal>
      </Container>
    </section>
  );
}
