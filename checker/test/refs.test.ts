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

  test("cyclic objects are refused without recursive traversal", () => {
    const self: { self?: unknown } = {};
    self.self = self;
    expect(externalRefFindings(self)).toEqual([
      expect.objectContaining({ code: "cyclic-alias-not-allowed", path: "/self" }),
    ]);

    const first: { next?: unknown } = {};
    const second = { next: first };
    first.next = second;
    expect(externalRefFindings(first)).toEqual([
      expect.objectContaining({ code: "cyclic-alias-not-allowed", path: "/next/next" }),
    ]);
  });

  test("shared non-cyclic aliases are scanned once and still reject external refs", () => {
    const shared = { $ref: "https://example.com/schema.json" };
    expect(externalRefFindings({ first: shared, second: shared }).map((finding) => finding.code)).toEqual([
      "external-ref-not-allowed",
    ]);
  });

  test("excessive nesting stops at the bounded scan depth", () => {
    const root: Record<string, unknown> = {};
    let cursor = root;
    for (let depth = 0; depth < 258; depth += 1) {
      const next: Record<string, unknown> = {};
      cursor.next = next;
      cursor = next;
    }
    expect(externalRefFindings(root)).toEqual([
      expect.objectContaining({ code: "reference-scan-limit" }),
    ]);
  });
});
