import { parseDocument } from "yaml";
import { breakingAsyncApi, validateAsyncApi } from "./engines/asyncapi.ts";
import { lintAsyncApi, lintOpenApi } from "./engines/spectral.ts";
import { ENGINES } from "./engines/index.ts";
import { finalizeFindings, type Finding } from "./findings.ts";
import type { CheckRequest, CheckResponse } from "./protocol.ts";
import { externalRefFindings } from "./refs.ts";

export type { CheckRequest, CheckResponse } from "./protocol.ts";

/**
 * The dispatch: parse once (YAML reads JSON too) for the `$ref` pre-scan and the Swagger 2.0
 * refusal, then the engines per type — for ASYNCAPI with a `previousContent` (the ACTIVE version
 * the JVM chose as the breaking-change baseline) the @asyncapi/diff pass runs beside them; the
 * JVM computes OPENAPI/ODCS breaking changes itself and never sends a previous document for them. Unparseable text is not the checker's finding to make —
 * the JVM's SYNTAX gate rejected it before calling — but a stray one still answers, as SYNTAX.
 */
export async function check(request: CheckRequest): Promise<CheckResponse> {
  const findings = await collect(request);
  return { findings: finalizeFindings(findings), engine: [...ENGINES] };
}

async function collect(request: CheckRequest): Promise<Finding[]> {
  const parsed = parseDocument(request.content, { uniqueKeys: false });
  if (parsed.errors.length > 0) {
    return parsed.errors.map((error) => ({
      severity: "ERROR" as const,
      source: "SYNTAX" as const,
      code: "parser",
      message: error.message,
      line: error.linePos?.[0]?.line,
      column: error.linePos?.[0]?.col,
    }));
  }
  let root: unknown;
  try {
    root = parsed.toJS() as unknown;
  } catch {
    return [unsafeExpansionFinding()];
  }
  const previous = request.type === "ASYNCAPI" ? parseForRefScan(request.previousContent) : undefined;
  const external = [...externalRefFindings(root), ...externalRefFindings(previous?.root)];
  if (external.length > 0) return external;

  switch (request.type) {
    case "OPENAPI":
      if (isSwagger2(root)) {
        return [
          {
            severity: "ERROR",
            source: "SCHEMA",
            code: "unsupported-version",
            message: "Swagger 2.0 documents are not supported — convert to OpenAPI 3.x",
            path: "/swagger",
          },
        ];
      }
      return lintOpenApi(request.content);
    case "ASYNCAPI": {
      let breakingWork: Promise<Finding[]> = Promise.resolve([]);
      if (request.previousContent !== undefined) {
        breakingWork = previous?.malformed || previous?.unsafe
          ? Promise.resolve([skippedBaselineFinding(previous.unsafe ? "unsafe YAML alias expansion" : "the previous document does not parse")])
          : breakingAsyncApi(request.previousContent, request.content);
      }
      const [semantic, lint, breaking] = await Promise.all([
        validateAsyncApi(request.content),
        lintAsyncApi(request.content),
        breakingWork,
      ]);
      return [...semantic, ...lint, ...breaking];
    }
    case "ODCS":
      // The JVM's JSON Schema validation is the whole ODCS gate in milestone 1.
      return [];
  }
}

/** Parse a baseline only for the offline guard; the diff engine owns malformed-baseline reporting. */
function parseForRefScan(content: string | undefined): { root: unknown; malformed: boolean; unsafe: boolean } | undefined {
  if (content === undefined) return undefined;
  const parsed = parseDocument(content, { uniqueKeys: false });
  if (parsed.errors.length > 0) return { root: undefined, malformed: true, unsafe: false };
  try {
    return { root: parsed.toJS() as unknown, malformed: false, unsafe: false };
  } catch {
    return { root: undefined, malformed: false, unsafe: true };
  }
}

function unsafeExpansionFinding(): Finding {
  return {
    severity: "ERROR",
    source: "SCHEMA",
    code: "unsafe-yaml-alias-expansion",
    message: "YAML alias expansion exceeds the safe document limit",
  };
}

function skippedBaselineFinding(reason: string): Finding {
  return {
    severity: "INFO",
    source: "BREAKING",
    code: "asyncapi-diff-skipped",
    message: `Breaking changes against the active version could not be computed — ${reason}`,
  };
}

function isSwagger2(root: unknown): boolean {
  return root !== null && typeof root === "object" && "swagger" in (root as object);
}
