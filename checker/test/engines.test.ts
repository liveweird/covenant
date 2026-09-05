import { describe, expect, test } from "vitest";
import { ENGINES } from "../src/engines/index.ts";

describe("ENGINES", () => {
  test("every engine reports a real version (an exports map must not hide package.json)", () => {
    expect(ENGINES.map((e) => e.name)).toEqual(["@stoplight/spectral-core", "@stoplight/spectral-rulesets", "@asyncapi/parser"]);
    for (const engine of ENGINES) expect(engine.version).toMatch(/^\d+\.\d+\.\d+/);
  });
});
