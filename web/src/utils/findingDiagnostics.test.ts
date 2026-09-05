import { describe, expect, test } from "vitest";
import { toDiagnostics } from "./findingDiagnostics";

const text = "openapi: 3.1.0\ninfo:\n  title: T\npaths: {}";

describe("toDiagnostics", () => {
  test("maps a positioned finding to its line from the column to the line end, with the severity and source label", () => {
    const [d] = toDiagnostics([{ severity: "WARN", source: "LINT", code: "info-contact", message: "m", line: 2, column: 1 }], text);
    expect(d).toEqual({ from: 15, to: 20, severity: "warning", message: "m", source: "LINT · info-contact" });
  });

  test("a column past the line end still yields a visible one-character range; ERROR/INFO map to error/info", () => {
    const [err, info] = toDiagnostics(
      [
        { severity: "ERROR", source: "SYNTAX", code: "YAML", message: "e", line: 1, column: 40 },
        { severity: "INFO", source: "SCHEMA", code: "x", message: "i", line: 3, column: 3 },
      ],
      text,
    );
    expect(err.severity).toBe("error");
    expect(err.from).toBe(14);
    expect(err.to).toBe(15);
    expect(info.severity).toBe("info");
    expect(info.from).toBe(23);
  });

  test("drops findings without a line and clamps a line beyond the text", () => {
    const out = toDiagnostics(
      [
        { severity: "WARN", source: "SYSTEM", code: "CHECKER_UNAVAILABLE", message: "m" },
        { severity: "ERROR", source: "SCHEMA", code: "x", message: "m", line: 99 },
      ],
      text,
    );
    expect(out).toHaveLength(1);
    expect(out[0].from).toBe(text.lastIndexOf("\n") + 1);
    expect(out[0].to).toBe(text.length);
  });

  test("an empty document yields empty ranges without throwing", () => {
    expect(toDiagnostics([{ severity: "ERROR", source: "SYNTAX", code: "x", message: "m", line: 1, column: 1 }], "")).toEqual([
      { from: 0, to: 0, severity: "error", message: "m", source: "SYNTAX · x" },
    ]);
  });
});
