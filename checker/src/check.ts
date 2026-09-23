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
    const unsafe = unsafeExpansionFinding();
    return request.type === "ASYNCAPI" && request.previousContent !== undefined
      ? [unsafe, skippedBaselineFinding("the current document exceeds safe YAML alias expansion")]
      : [unsafe];
  }
  const external = externalRefFindings(root);
  if (external.length > 0) {
    return request.type === "ASYNCAPI" && request.previousContent !== undefined
      ? [...external, skippedBaselineFinding("the current document contains unresolved references or exceeds the safe reference-scan limit")]
      : external;
  }
  const previous = request.type === "ASYNCAPI" ? parseForRefScan(request.previousContent) : undefined;
  const previousRefFindings = externalRefFindings(previous?.root);
  const previousExternalRefs = previousRefFindings.filter((finding) => finding.code === "external-ref-not-allowed");
  if (previousRefFindings.length > previousExternalRefs.length) {
    return [skippedBaselineFinding("the previous document exceeds safe reference-scan limits")];
  }

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
        let skipReason: string | undefined;
        if (previous?.unsafe) {
          skipReason = "unsafe YAML alias expansion";
        } else if (previous?.malformed) {
          skipReason = "the previous document does not parse";
        } else if (previousExternalRefs.length > 0) {
          skipReason = "the previous document contains external references that Covenant does not resolve";
        }
        breakingWork = skipReason === undefined
          ? breakingAsyncApi(request.previousContent, request.content)
          : Promise.resolve([skippedBaselineFinding(skipReason)]);
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
