import type { Finding } from "./findings.ts";

/**
 * The offline guarantee's first layer: every `$ref` that does not point INTO the document
 * (`#/...`) is refused here, before any engine gets to resolve it. Spectral's resolver is
 * configured with no readers, but `@asyncapi/parser`'s cannot be told to drop its default
 * http/https/file readers — so the scan is load-bearing, not belt-and-braces. Layer two is
 * the container's missing route out (compose `internal: true`, the k8s NetworkPolicy).
 */
export function externalRefFindings(document: unknown): Finding[] {
  const findings: Finding[] = [];
  walk(document, [], findings);
  return findings;
}

function walk(node: unknown, path: string[], findings: Finding[]): void {
  if (Array.isArray(node)) {
    node.forEach((entry, index) => walk(entry, [...path, String(index)], findings));
    return;
  }
  if (node === null || typeof node !== "object") return;
  for (const [key, value] of Object.entries(node as Record<string, unknown>)) {
    if (key === "$ref" && typeof value === "string" && !value.startsWith("#")) {
      findings.push({
        severity: "ERROR",
        source: "SCHEMA",
        code: "external-ref-not-allowed",
        message: `External $ref "${value}" is not resolved — Covenant checks self-contained documents only`,
        path: toPointer([...path, key]),
      });
      continue;
    }
    walk(value, [...path, key], findings);
  }
}

function toPointer(segments: string[]): string {
  return "/" + segments.map((s) => s.replaceAll("~", "~0").replaceAll("/", "~1")).join("/");
}
