// The pure comparison behind the sync modal (Toadie's, over the raw document text): which side
// moved since the last sync, and the line diff to show — one equality for the badges AND the diff.

import { diffLines, type DiffLine } from "./lineDiff";

export interface SyncComparison {
  /** The stored text and the repo copy are identical — nothing to overwrite. */
  inSync: boolean;
  /** The stored text moved since the sync (`updatedAt > lastSyncedAt`, synced rows only). */
  localChanged: boolean;
  /** The repo copy differs from the baseline snapshot taken at the last sync. */
  repoChanged: boolean;
  /** Stored → repo line diff; null while either side is missing or already in sync. */
  diff: DiffLine[] | null;
}

/**
 * The edited-since-sync predicate — meaningful only while the row HAS been synced
 * (`lastSyncedAt > 0`): a sync stamps `updatedAt` and `lastSyncedAt` equal, and a changed or
 * cleared reference resets `lastSyncedAt` to 0, where the comparison carries no drift meaning.
 */
export function hasLocalChanges(version: { updatedAt: number; lastSyncedAt: number }): boolean {
  return version.lastSyncedAt > 0 && version.updatedAt > version.lastSyncedAt;
}

export function compareSyncSides(input: {
  /** The stored document text. */
  current: string;
  /** The fetched repo copy; null while loading or failed. */
  repo: string | null;
  /** The baseline stored at the last sync; null = never synced. */
  baseline: string | null;
  updatedAt: number;
  /** 0 = never synced. */
  lastSyncedAt: number;
}): SyncComparison {
  const { current, repo, baseline, updatedAt, lastSyncedAt } = input;
  const inSync = repo != null && current === repo;
  return {
    inSync,
    localChanged: hasLocalChanges({ updatedAt, lastSyncedAt }),
    repoChanged: repo != null && baseline != null && repo !== baseline,
    diff: repo != null && !inSync ? diffLines(current, repo) : null,
  };
}
