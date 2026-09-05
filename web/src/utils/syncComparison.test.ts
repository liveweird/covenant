import { describe, expect, test } from "vitest";
import { compareSyncSides, hasLocalChanges } from "./syncComparison";

describe("syncComparison", () => {
  test("local changes need a sync stamp to mean anything", () => {
    expect(hasLocalChanges({ updatedAt: 10, lastSyncedAt: 0 })).toBe(false);
    expect(hasLocalChanges({ updatedAt: 10, lastSyncedAt: 10 })).toBe(false);
    expect(hasLocalChanges({ updatedAt: 11, lastSyncedAt: 10 })).toBe(true);
  });

  test("attributes a difference to the side that moved and diffs stored → repo", () => {
    const base = { current: "a\nb\n", baseline: "a\nb\n", updatedAt: 10, lastSyncedAt: 10 };
    expect(compareSyncSides({ ...base, repo: "a\nb\n" })).toMatchObject({ inSync: true, localChanged: false, repoChanged: false, diff: null });
    const repoMoved = compareSyncSides({ ...base, repo: "a\nc\n" });
    expect(repoMoved).toMatchObject({ inSync: false, localChanged: false, repoChanged: true });
    expect(repoMoved.diff?.filter((l) => l.kind !== "same").map((l) => `${l.kind}:${l.text}`)).toEqual(["removed:b", "added:c"]);
    const bothMoved = compareSyncSides({ ...base, current: "a\nx\n", updatedAt: 11, repo: "a\nc\n" });
    expect(bothMoved).toMatchObject({ inSync: false, localChanged: true, repoChanged: true });
    // Never synced: no baseline, no attribution — the diff still says what would change.
    const never = compareSyncSides({ current: "a\n", repo: "b\n", baseline: null, updatedAt: 5, lastSyncedAt: 0 });
    expect(never).toMatchObject({ inSync: false, localChanged: false, repoChanged: false });
    expect(never.diff).not.toBeNull();
    expect(compareSyncSides({ ...base, repo: null }).diff).toBeNull();
  });
});
