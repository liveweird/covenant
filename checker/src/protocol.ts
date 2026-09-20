import type { Finding } from "./findings.ts";

type ContractType = "OPENAPI" | "ASYNCAPI" | "ODCS";
const CONTRACT_TYPES: readonly ContractType[] = ["OPENAPI", "ASYNCAPI", "ODCS"];

export interface EngineVersion {
  name: string;
  version: string;
}

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
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  const request = value as Record<string, unknown>;
  const allowed = new Set(["type", "content", "previousContent"]);
  return (
    Object.keys(request).every((key) => allowed.has(key)) &&
    typeof request.type === "string" &&
    (CONTRACT_TYPES as readonly string[]).includes(request.type) &&
    typeof request.content === "string" &&
    (request.previousContent === undefined || typeof request.previousContent === "string")
  );
}
