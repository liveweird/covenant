import { describe, expect, test } from "vitest";
import { externalRefFindings } from "../src/refs.ts";

describe("externalRefFindings", () => {
  test("internal pointers pass, external refs are named with their pointer", () => {
    const doc = {
      a: { $ref: "#/components/schemas/X" },
      list: [{ $ref: "https://example.com/x.json" }, { nested: { $ref: "./local.yaml#/Foo" } }],
      "we/ird": { $ref: "file:///etc/passwd" },
    };
    const findings = externalRefFindings(doc);
    expect(findings.map((f) => f.path)).toEqual(["/list/0/$ref", "/list/1/nested/$ref", "/we~1ird/$ref"]);
    for (const finding of findings) {
      expect(finding).toMatchObject({ severity: "ERROR", source: "SCHEMA", code: "external-ref-not-allowed" });
    }
  });

  test("a $ref that is not a string is ignored (an engine will complain about the shape)", () => {
    expect(externalRefFindings({ $ref: 42 })).toEqual([]);
    expect(externalRefFindings("scalar")).toEqual([]);
    expect(externalRefFindings(null)).toEqual([]);
  });
});
