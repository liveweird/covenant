import { describe, expect, test } from "vitest";
import { bumpSemver, compareSemver, isValidSemver, parseSemver } from "./semver";

const v = (s: string) => parseSemver(s)!;

describe("semver", () => {
  test("parses the grammar: core, prerelease identifiers, build metadata", () => {
    expect(parseSemver("1.2.3")).toEqual({ major: 1, minor: 2, patch: 3, prerelease: null, build: null });
    expect(parseSemver("1.2.3-rc.1+build.7")).toEqual({ major: 1, minor: 2, patch: 3, prerelease: ["rc", "1"], build: "build.7" });
    expect(parseSemver("0.0.0-alpha-1.x")?.prerelease).toEqual(["alpha-1", "x"]);
  });

  test("rejects what the spec rejects", () => {
    for (const bad of ["", "1", "1.2", "01.2.3", "1.2.3-", "1.2.3-01", "v1.2.3", "1.2.3 ", `1.2.3${".4"}`, "a.b.c", "1.2.3-rc..1", "1.2.3+", "1.2.3+a_b", "1".repeat(70)]) {
      expect(isValidSemver(bad), bad).toBe(false);
    }
  });

  test("orders by precedence — numbers, release above prerelease, identifiers numeric-before-alpha", () => {
    const ordered = ["1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.0.1", "1.1.0", "2.0.0"];
    for (let i = 1; i < ordered.length; i++) {
      expect(compareSemver(v(ordered[i - 1]), v(ordered[i])), `${ordered[i - 1]} < ${ordered[i]}`).toBeLessThan(0);
    }
    expect(compareSemver(v("1.0.0+a"), v("1.0.0+b"))).toBe(0);
  });

  test("bumps one axis and turns a prerelease into its release on patch", () => {
    expect(bumpSemver("1.2.3", "major")).toBe("2.0.0");
    expect(bumpSemver("1.2.3", "minor")).toBe("1.3.0");
    expect(bumpSemver("1.2.3", "patch")).toBe("1.2.4");
    expect(bumpSemver("2.0.0-rc.1", "patch")).toBe("2.0.0");
    expect(bumpSemver("2.0.0-rc.1", "minor")).toBe("2.1.0");
    expect(bumpSemver("nope", "patch")).toBeNull();
  });
});
