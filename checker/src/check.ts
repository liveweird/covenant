import { parseDocument } from "yaml";
import { validateAsyncApi } from "./engines/asyncapi.ts";
import { lintAsyncApi, lintOpenApi } from "./engines/spectral.ts";
import { ENGINES, type EngineVersion } from "./engines/index.ts";
import { CONTRACT_TYPES, finalizeFindings, type ContractType, type Finding } from "./findings.ts";
import { externalRefFindings } from "./refs.ts";

export interface CheckRequest {
  type: ContractType;
  content: string;
  previousContent?: string;
}

export interface CheckResponse {
  findings: Finding[];
  engine: EngineVersion[];
}

export function isCheckRequest(value: unknown): value is CheckRequest {
  if (value === null || typeof value !== "object") return false;
  const v = value as Record<string, unknown>;
  return (
    typeof v.type === "string" &&
    (CONTRACT_TYPES as readonly string[]).includes(v.type) &&
    typeof v.content === "string" &&
    (v.previousContent === undefined || typeof v.previousContent === "string")
  );
}

/**
 * The dispatch: parse once (YAML reads JSON too) for the `$ref` pre-scan and the Swagger 2.0
 * refusal, then the engines per type. Unparseable text is not the checker's finding to make —
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
  const root = parsed.toJS() as unknown;
  const external = externalRefFindings(root);
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
      const [semantic, lint] = await Promise.all([validateAsyncApi(request.content), lintAsyncApi(request.content)]);
      return [...semantic, ...lint];
    }
    case "ODCS":
      // The JVM's JSON Schema validation is the whole ODCS gate in milestone 1.
      return [];
  }
}

function isSwagger2(root: unknown): boolean {
  return root !== null && typeof root === "object" && "swagger" in (root as object);
}
