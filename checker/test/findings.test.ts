import { describe, expect, test } from "vitest";
import { finalizeFindings, fromDiagnostic, mapSeverity, mapSource, MAX_FINDINGS, toJsonPointer, type Finding } from "../src/findings.ts";

describe("severity and source mapping", () => {
  test("Spectral severities fold into the three wire levels (hints become INFO)", () => {
    expect(mapSeverity(0)).toBe("ERROR");
    expect(mapSeverity(1)).toBe("WARN");
    expect(mapSeverity(2)).toBe("INFO");
    expect(mapSeverity(3)).toBe("INFO");
  });

  test("structural rules are SCHEMA, the parser is SYNTAX, the rest keeps the engine's fallback", () => {
    expect(mapSource("parser", "LINT")).toBe("SYNTAX");
    expect(mapSource("oas3-schema", "LINT")).toBe("SCHEMA");
    expect(mapSource("asyncapi-schema", "SEMANTIC")).toBe("SCHEMA");
    expect(mapSource("invalid-ref", "LINT")).toBe("SCHEMA");
    expect(mapSource("asyncapi-3-document-resolved", "LINT")).toBe("SCHEMA");
    expect(mapSource("asyncapi-document-resolved", "SEMANTIC")).toBe("SCHEMA");
    expect(mapSource("operation-operationId", "LINT")).toBe("LINT");
    expect(mapSource("asyncapi-servers", "SEMANTIC")).toBe("SEMANTIC");
  });

  test("paths become RFC 6901 pointers, 0-based positions become 1-based", () => {
    expect(toJsonPointer(["paths", "/pets", "get"])).toBe("/paths/~1pets/get");
    expect(toJsonPointer(["a~b", 0])).toBe("/a~0b/0");
    expect(toJsonPointer([])).toBeUndefined();
    const finding = fromDiagnostic(
      {
        code: "info-contact",
        message: "Info object must have a contact",
        severity: 1,
        path: ["info"],
        range: { start: { line: 1, character: 0 }, end: { line: 1, character: 4 } },
      },
      "LINT",
    );
    expect(finding).toEqual({
      severity: "WARN",
      source: "LINT",
      code: "info-contact",
      message: "Info object must have a contact",
      path: "/info",
      line: 2,
      column: 1,
    });
  });
});

describe("finalizeFindings", () => {
  const f = (severity: Finding["severity"], code: string, line?: number, path?: string): Finding => ({
    severity,
    source: "LINT",
    code,
    message: code,
    line,
    path,
  });

  test("sorts severity → line → code and drops (code, path) duplicates", () => {
    const sorted = finalizeFindings([f("INFO", "b", 1), f("ERROR", "z", 9), f("WARN", "a", 3), f("ERROR", "a", 2), f("ERROR", "a", 2)]);
    // The WARN "a" at line 3 is a different finding from the ERROR "a" at line 2 — only exact (code, path, line) twins collapse.
    expect(sorted.map((x) => `${x.severity}:${x.code}`)).toEqual(["ERROR:a", "ERROR:z", "WARN:a", "INFO:b"]);
  });

  test("positionless findings sort after positioned ones of the same severity", () => {
    const sorted = finalizeFindings([f("WARN", "later"), f("WARN", "first", 4)]);
    expect(sorted.map((x) => x.code)).toEqual(["first", "later"]);
  });

  test("caps at MAX_FINDINGS and appends the truncation marker", () => {
    const many = Array.from({ length: MAX_FINDINGS + 7 }, (_, i) => f("WARN", `w${i}`, i, `/p/${i}`));
    const out = finalizeFindings(many);
    expect(out).toHaveLength(MAX_FINDINGS + 1);
    expect(out.at(-1)).toMatchObject({ source: "SYSTEM", code: "findings-truncated", severity: "INFO" });
    expect(out.at(-1)?.message).toContain("7 further findings");
  });
});
