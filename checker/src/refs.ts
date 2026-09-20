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
  const active = new WeakSet<object>();
  const completed = new WeakSet<object>();
  const stack: Frame[] = [{ node: document, path: [], leaving: false }];
  let visited = 0;
  while (stack.length > 0) {
    const frame = stack.pop();
    if (!frame) break;
    const object = asObject(frame.node);
    if (!object) continue;
    if (frame.leaving) {
      active.delete(object);
      completed.add(object);
      continue;
    }
    if (active.has(object)) {
      findings.push(boundaryFinding("cyclic-alias-not-allowed", "Cyclic YAML aliases are not supported", frame.path));
      continue;
    }
    if (completed.has(object)) continue;
    visited += 1;
    if (visited > MAX_NODES || frame.path.length > MAX_DEPTH) {
      findings.push(boundaryFinding("reference-scan-limit", "Document structure exceeds the safe reference-scan limit", frame.path));
      break;
    }
    active.add(object);
    stack.push({ ...frame, leaving: true });
    const entries = Array.isArray(object) ? object.map((value, index) => [String(index), value] as const) : Object.entries(object);
    for (let index = entries.length - 1; index >= 0; index -= 1) {
      const entry = entries[index];
      if (!entry) continue;
      const [key, value] = entry;
      const path = [...frame.path, key];
      if (key === "$ref" && typeof value === "string" && !value.startsWith("#")) {
        findings.push({
          severity: "ERROR",
          source: "SCHEMA",
          code: "external-ref-not-allowed",
          message: `External $ref "${value}" is not resolved — Covenant checks self-contained documents only`,
          path: toPointer(path),
        });
      } else {
        stack.push({ node: value, path, leaving: false });
      }
    }
  }
  return findings;
}

const MAX_NODES = 100_000;
const MAX_DEPTH = 256;

type Frame = { node: unknown; path: string[]; leaving: boolean };

function asObject(node: unknown): object | undefined {
  return node !== null && typeof node === "object" ? node : undefined;
}

function boundaryFinding(code: string, message: string, path: string[]): Finding {
  return { severity: "ERROR", source: "SCHEMA", code, message, path: toPointer(path) };
}

function toPointer(segments: string[]): string {
  return "/" + segments.map((s) => s.replaceAll("~", "~0").replaceAll("/", "~1")).join("/");
}
