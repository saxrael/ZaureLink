import { statSync } from "node:fs";
import path from "node:path";

export type PublicAsset = {
  /** True only when the path resolves to a real file under public/. */
  exists: boolean;
  /** Human-readable size, e.g. "48MB" — null when the file is absent. */
  sizeLabel: string | null;
};

/**
 * Checks whether a file is actually present in public/, on the server, at render time.
 *
 * The point is to stop the page asserting things that aren't true yet. Both the demo recording and
 * the production APK are landing later than the page that links to them, and a hard-coded href to a
 * missing file doesn't degrade — it 404s on click, which is worse than an honest "not yet". Sections
 * that depend on a file ask here first and render a waiting state when it isn't there.
 *
 * Server-only (uses node:fs), so this must not be imported into a "use client" module. Evaluated
 * during `next build` for statically rendered pages: dropping a file into public/ therefore requires
 * a rebuild before the page notices it.
 */
export function publicAsset(relativePath: string): PublicAsset {
  try {
    const stats = statSync(path.join(process.cwd(), "public", relativePath));
    return stats.isFile()
      ? { exists: true, sizeLabel: formatBytes(stats.size) }
      : { exists: false, sizeLabel: null };
  } catch {
    // ENOENT is the expected case before the asset ships, not an error worth surfacing.
    return { exists: false, sizeLabel: null };
  }
}

function formatBytes(bytes: number): string {
  return bytes >= 1_000_000_000
    ? `${(bytes / 1_000_000_000).toFixed(1)}GB`
    : `${Math.round(bytes / 1_000_000)}MB`;
}
