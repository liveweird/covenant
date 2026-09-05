// Strict SemVer 2.0.0 — the official grammar plus the precedence rules (spec §11), hand-rolled
// because ~60 lines do not justify the `semver` package. Mirrors the server's SemVer.kt: the
// two must agree on what is valid and what is "greater than the highest existing version".

export type SemVer = {
  major: number;
  minor: number;
  patch: number;
  /** The dot-separated prerelease identifiers, or null for a release. */
  prerelease: string[] | null;
  /** Build metadata — carried, never compared. */
  build: string | null;
};

/** Mirrors the server (SemVer.MAX_LENGTH, the V10 column). */
export const MAX_VERSION_LENGTH = 64;

// semver.org's grammar, applied piecewise (the single official regex trips the lint's
// complexity budget and reads worse): core `X.Y.Z` with no leading zeros, then the optional
// `-prerelease` (dot-separated identifiers, numeric ones without leading zeros) and `+build`.
const CORE = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/;
const NUMERIC_IDENTIFIER = /^(0|[1-9]\d*)$/;
const ALPHANUMERIC_IDENTIFIER = /^[0-9A-Za-z-]+$/;

function validPrerelease(identifiers: string[]): boolean {
  return identifiers.every((id) => id.length > 0 && ALPHANUMERIC_IDENTIFIER.test(id) && (!/^\d+$/.test(id) || NUMERIC_IDENTIFIER.test(id)));
}

export function parseSemver(raw: string): SemVer | null {
  if (raw.length > MAX_VERSION_LENGTH) return null;
  const plus = raw.indexOf("+");
  const build = plus === -1 ? null : raw.slice(plus + 1);
  const withoutBuild = plus === -1 ? raw : raw.slice(0, plus);
  if (build !== null && !build.split(".").every((id) => id.length > 0 && ALPHANUMERIC_IDENTIFIER.test(id))) return null;
  const dash = withoutBuild.indexOf("-");
  const core = dash === -1 ? withoutBuild : withoutBuild.slice(0, dash);
  const prerelease = dash === -1 ? null : withoutBuild.slice(dash + 1).split(".");
  const m = CORE.exec(core);
  if (!m) return null;
  if (prerelease !== null && !validPrerelease(prerelease)) return null;
  return { major: Number(m[1]), minor: Number(m[2]), patch: Number(m[3]), prerelease, build };
}

export const isValidSemver = (raw: string): boolean => parseSemver(raw) !== null;

const NUMERIC = /^\d+$/;

function compareIdentifiers(a: string, b: string): number {
  const an = NUMERIC.test(a);
  const bn = NUMERIC.test(b);
  if (an && bn) return Number(a) - Number(b);
  // Numeric identifiers always have lower precedence than alphanumeric ones.
  if (an) return -1;
  if (bn) return 1;
  return a < b ? -1 : a > b ? 1 : 0;
}

/** Spec §11 precedence: numbers, then a release above any of its prereleases, then identifier by identifier. */
export function compareSemver(a: SemVer, b: SemVer): number {
  if (a.major !== b.major) return a.major - b.major;
  if (a.minor !== b.minor) return a.minor - b.minor;
  if (a.patch !== b.patch) return a.patch - b.patch;
  if (a.prerelease === null && b.prerelease === null) return 0;
  if (a.prerelease === null) return 1;
  if (b.prerelease === null) return -1;
  const length = Math.min(a.prerelease.length, b.prerelease.length);
  for (let i = 0; i < length; i++) {
    const c = compareIdentifiers(a.prerelease[i], b.prerelease[i]);
    if (c !== 0) return c;
  }
  return a.prerelease.length - b.prerelease.length;
}

export type BumpKind = "major" | "minor" | "patch";

/** The next release after `version` on one axis — a prerelease bumps to its own release first. */
export function bumpSemver(version: string, kind: BumpKind): string | null {
  const v = parseSemver(version);
  if (!v) return null;
  if (v.prerelease !== null && kind === "patch") return `${v.major}.${v.minor}.${v.patch}`;
  switch (kind) {
    case "major":
      return `${v.major + 1}.0.0`;
    case "minor":
      return `${v.major}.${v.minor + 1}.0`;
    default:
      return `${v.major}.${v.minor}.${v.patch + 1}`;
  }
}
