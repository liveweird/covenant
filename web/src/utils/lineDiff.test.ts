import { describe, expect, test } from "vitest";
import { collapseUnchanged, diffLines, diffStats } from "./lineDiff";

describe("lineDiff", () => {
  test("marks removed lines before added ones at a divergence and keeps the common frame", () => {
    const diff = diffLines("a\nb\nc\nd", "a\nx\nc\nd");
    expect(diff).toEqual([
      { kind: "same", text: "a" },
      { kind: "removed", text: "b" },
      { kind: "added", text: "x" },
      { kind: "same", text: "c" },
      { kind: "same", text: "d" },
    ]);
    expect(diffStats(diff)).toEqual({ added: 1, removed: 1 });
  });

  test("identical texts are all-same; empty against text is all-added", () => {
    expect(diffLines("a\nb", "a\nb").every((l) => l.kind === "same")).toBe(true);
    expect(diffLines("", "a\nb")).toEqual([{ kind: "removed", text: "" }, { kind: "added", text: "a" }, { kind: "added", text: "b" }]);
  });

  test("a pair too large for the table falls back to a wholesale replace", () => {
    const a = Array.from({ length: 2500 }, (_, i) => `a${i}`).join("\n");
    const b = Array.from({ length: 2500 }, (_, i) => `b${i}`).join("\n");
    const diff = diffLines(a, b);
    expect(diff.filter((l) => l.kind === "removed")).toHaveLength(2500);
    expect(diff.filter((l) => l.kind === "added")).toHaveLength(2500);
  });

  test("collapseUnchanged keeps context lines around changes and marks the hidden runs", () => {
    const same = (n: number) => Array.from({ length: n }, (_, i) => ({ kind: "same" as const, text: `s${i}` }));
    const rows = collapseUnchanged([...same(10), { kind: "added", text: "+" }, ...same(10)], 3);
    expect(rows[0]).toEqual({ kind: "skipped", count: 7 });
    expect(rows.slice(1, 4).every((r) => r.kind === "same")).toBe(true);
    expect(rows[4]).toEqual({ kind: "added", text: "+" });
    expect(rows.slice(5, 8).every((r) => r.kind === "same")).toBe(true);
    expect(rows[8]).toEqual({ kind: "skipped", count: 7 });
    expect(rows).toHaveLength(9);
  });

  test("collapseUnchanged leaves short runs alone and collapses an identical document to one marker", () => {
    const short = collapseUnchanged([{ kind: "same", text: "a" }, { kind: "removed", text: "b" }, { kind: "same", text: "c" }]);
    expect(short).toHaveLength(3);
    expect(collapseUnchanged(Array.from({ length: 5 }, () => ({ kind: "same" as const, text: "x" })))).toEqual([{ kind: "skipped", count: 5 }]);
  });
});
