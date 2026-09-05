// A line-level diff (LCS) for the version comparison view — Toadie's `yamlDiff.ts`, hardened
// for real documents: the common prefix/suffix is trimmed before the O(n·m) table (a typical
// version bump changes a few lines of a long file), and a pair too large for the table falls
// back to a whole-file replace rather than freezing the tab. Hand-rolled on purpose: no diff
// dependency for ~80 lines of classic DP.

export type DiffLine = {
  kind: "same" | "removed" | "added";
  text: string;
};

/** Above this many cells the DP table is skipped (≈ 32 MB of numbers) — replace wholesale. */
const MAX_TABLE_CELLS = 4_000_000;

function lcsDiff(a: string[], b: string[]): DiffLine[] {
  if (a.length * b.length > MAX_TABLE_CELLS) {
    return [...a.map((text): DiffLine => ({ kind: "removed", text })), ...b.map((text): DiffLine => ({ kind: "added", text }))];
  }
  // lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..].
  const lcs: number[][] = Array.from({ length: a.length + 1 }, () => new Array<number>(b.length + 1).fill(0));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) {
      out.push({ kind: "same", text: a[i] });
      i++;
      j++;
    } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
      out.push({ kind: "removed", text: a[i] });
      i++;
    } else {
      out.push({ kind: "added", text: b[j] });
      j++;
    }
  }
  while (i < a.length) out.push({ kind: "removed", text: a[i++] });
  while (j < b.length) out.push({ kind: "added", text: b[j++] });
  return out;
}

/** The line-by-line diff of [before] → [after]: removed lines first at each divergence. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before.split("\n");
  const b = after.split("\n");
  let prefix = 0;
  while (prefix < a.length && prefix < b.length && a[prefix] === b[prefix]) prefix++;
  let suffix = 0;
  while (suffix < a.length - prefix && suffix < b.length - prefix && a[a.length - 1 - suffix] === b[b.length - 1 - suffix]) suffix++;
  const same = (text: string): DiffLine => ({ kind: "same", text });
  return [
    ...a.slice(0, prefix).map(same),
    ...lcsDiff(a.slice(prefix, a.length - suffix), b.slice(prefix, b.length - suffix)),
    ...a.slice(a.length - suffix).map(same),
  ];
}

export type DiffRow = DiffLine | { kind: "skipped"; count: number };

/**
 * The "hide unchanged" view: unchanged runs longer than 2×`context` collapse to a marker row,
 * keeping `context` lines on each side of every change (a diff of two identical texts is one
 * marker).
 */
export function collapseUnchanged(diff: readonly DiffLine[], context = 3): DiffRow[] {
  const out: DiffRow[] = [];
  let run: DiffLine[] = [];
  const flush = (trailing: boolean) => {
    if (run.length === 0) return;
    const keepHead = out.length > 0 ? context : 0;
    const keepTail = trailing ? 0 : context;
    if (run.length > keepHead + keepTail) {
      out.push(...run.slice(0, keepHead));
      out.push({ kind: "skipped", count: run.length - keepHead - keepTail });
      if (keepTail > 0) out.push(...run.slice(run.length - keepTail));
    } else {
      out.push(...run);
    }
    run = [];
  };
  for (const line of diff) {
    if (line.kind === "same") {
      run.push(line);
    } else {
      flush(false);
      out.push(line);
    }
  }
  flush(true);
  return out;
}

/** The headline counts for the diff summary line. */
export function diffStats(diff: readonly DiffLine[]): { added: number; removed: number } {
  let added = 0;
  let removed = 0;
  for (const line of diff) {
    if (line.kind === "added") added++;
    else if (line.kind === "removed") removed++;
  }
  return { added, removed };
}
