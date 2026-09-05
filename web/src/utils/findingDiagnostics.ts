import type { Finding, Severity } from "../api/versions";

/**
 * A CodeMirror `Diagnostic`, spelled out here so this module (and its tests) never import
 * `@codemirror/*` — only `components/CodeEditor.tsx` may. Structurally identical, so the
 * editor passes these straight to `setDiagnostics`.
 */
export type EditorDiagnostic = {
  from: number;
  to: number;
  severity: "error" | "warning" | "info";
  message: string;
  source?: string;
};

const SEVERITY: Record<Severity, EditorDiagnostic["severity"]> = {
  ERROR: "error",
  WARN: "warning",
  INFO: "info",
};

/** The 0-based offset of each line's first character. */
function lineStarts(text: string): number[] {
  const starts = [0];
  for (let i = 0; i < text.length; i++) {
    if (text[i] === "\n") starts.push(i + 1);
  }
  return starts;
}

/**
 * Server findings → editor diagnostics. A finding with a 1-based `line` (and optional
 * `column`) marks that line from the column to the line's end (at least one character, so a
 * mark at a line's end stays visible); findings without a position are left out — the
 * FindingsPanel lists them, the gutter cannot point at them. Positions beyond the current
 * text (the document shrank since the check) are clamped.
 */
export function toDiagnostics(findings: readonly Finding[], text: string): EditorDiagnostic[] {
  const starts = lineStarts(text);
  const out: EditorDiagnostic[] = [];
  for (const finding of findings) {
    if (finding.line == null || finding.line < 1) continue;
    const lineIndex = Math.min(finding.line, starts.length) - 1;
    const lineStart = starts[lineIndex];
    const lineEnd = lineIndex + 1 < starts.length ? starts[lineIndex + 1] - 1 : text.length;
    const column = Math.max(1, finding.column ?? 1);
    const from = Math.min(lineStart + column - 1, lineEnd);
    const to = Math.max(from + 1, lineEnd);
    out.push({
      from: Math.min(from, text.length),
      to: Math.min(to, text.length),
      severity: SEVERITY[finding.severity],
      message: finding.message,
      source: `${finding.source} · ${finding.code}`,
    });
  }
  return out;
}
