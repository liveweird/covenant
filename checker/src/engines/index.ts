import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, join } from "node:path";

export interface EngineVersion {
  name: string;
  version: string;
}

const require = createRequire(import.meta.url);

/**
 * A package's version without requiring `<pkg>/package.json` — an `exports` map (Spectral's)
 * blocks that subpath. Resolve the entry file instead and walk up to its package.json.
 */
function versionOf(pkg: string): string {
  try {
    let dir = dirname(require.resolve(pkg));
    for (let depth = 0; depth < 6; depth++) {
      const candidate = join(dir, "package.json");
      if (existsSync(candidate)) {
        const parsed = JSON.parse(readFileSync(candidate, "utf8")) as { name?: string; version?: string };
        if (parsed.name === pkg && parsed.version) return parsed.version;
      }
      dir = dirname(dir);
    }
  } catch {
    // fall through — an unknown version is reported, never a crash at boot
  }
  return "unknown";
}

/** The engine roster the `/check` and `/healthz` responses report, read once at boot. */
export const ENGINES: readonly EngineVersion[] = [
  { name: "@stoplight/spectral-core", version: versionOf("@stoplight/spectral-core") },
  { name: "@stoplight/spectral-rulesets", version: versionOf("@stoplight/spectral-rulesets") },
  { name: "@asyncapi/parser", version: versionOf("@asyncapi/parser") },
];
