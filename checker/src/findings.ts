import type { IRuleResult } from "@stoplight/spectral-core";

/** The wire vocabulary shared with the JVM (`contracts/checks/Finding.kt`) — never re-mapped there. */
export type Severity = "ERROR" | "WARN" | "INFO";
export type Source = "SYNTAX" | "SCHEMA" | "SEMANTIC" | "LINT" | "BREAKING" | "CONFORMANCE" | "SYSTEM";

export interface Finding {
  severity: Severity;
  source: Source;
  code: string;
  message: string;
  /** A JSON pointer into the document (`/paths/~1pets/get`), when the engine located it. */
  path?: string;
  /** 1-based, when the engine located it. */
  line?: number;
  column?: number;
}

export type ContractType = "OPENAPI" | "ASYNCAPI" | "ODCS";
export const CONTRACT_TYPES: readonly ContractType[] = ["OPENAPI", "ASYNCAPI", "ODCS"];

/** At most this many findings leave the checker; a final INFO marks the cut. */
export const MAX_FINDINGS = 500;

const SEVERITY_ORDER: Record<Severity, number> = { ERROR: 0, WARN: 1, INFO: 2 };

/** Spectral's DiagnosticSeverity: Error=0, Warning=1, Information=2, Hint=3 — hints fold into INFO. */
export function mapSeverity(spectral: number): Severity {
  if (spectral <= 0) return "ERROR";
  if (spectral === 1) return "WARN";
  return "INFO";
}

/** Structural-validation rule codes → SCHEMA; the YAML/JSON parser → SYNTAX; the rest is LINT. */
export function mapSource(code: string, fallback: Source): Source {
  if (code === "parser") return "SYNTAX";
  if (code.endsWith("-schema") || code === "invalid-ref" || code.startsWith("asyncapi-document") || code.startsWith("asyncapi-3-document")) return "SCHEMA";
  return fallback;
}

/** RFC 6901 escaping for one pointer segment. */
function escapeSegment(segment: string | number): string {
  return String(segment).replaceAll("~", "~0").replaceAll("/", "~1");
}

export function toJsonPointer(path: ReadonlyArray<string | number>): string | undefined {
  if (path.length === 0) return undefined;
  return "/" + path.map(escapeSegment).join("/");
}

/** One Spectral/parser diagnostic → one wire finding (positions 0-based → 1-based). */
export function fromDiagnostic(result: IRuleResult, fallbackSource: Source): Finding {
  const code = String(result.code);
  return {
    severity: mapSeverity(result.severity),
    source: mapSource(code, fallbackSource),
    code,
    message: result.message,
    path: toJsonPointer(result.path),
    line: result.range.start.line + 1,
    column: result.range.start.character + 1,
  };
}

/** Severity → line → code ordering, deduplicated by (code, path, line), capped at MAX_FINDINGS. */
export function finalizeFindings(findings: readonly Finding[]): Finding[] {
  const seen = new Set<string>();
  const unique = findings.filter((f) => {
    const key = `${f.code}@${f.path ?? ""}@${f.line ?? ""}`;
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
  unique.sort(
    (a, b) =>
      SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] ||
      (a.line ?? Number.MAX_SAFE_INTEGER) - (b.line ?? Number.MAX_SAFE_INTEGER) ||
      a.code.localeCompare(b.code),
  );
  if (unique.length <= MAX_FINDINGS) return unique;
  return [
    ...unique.slice(0, MAX_FINDINGS),
    {
      severity: "INFO",
      source: "SYSTEM",
      code: "findings-truncated",
      message: `${unique.length - MAX_FINDINGS} further findings were not returned (cap ${MAX_FINDINGS})`,
    },
  ];
}
