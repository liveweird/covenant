import type { TFunction } from "i18next";
import type { ContractType } from "../api/contracts";
import type { EnvironmentResponse } from "../api/environments";
import { ApiError } from "../api/http";
import { saveErrorMessage } from "./saveError";

/** The leg each contract type tries through, and the environment target it needs. */
export type TryTarget = "http" | "kafka" | "postgres";

export function tryTargetOf(type: ContractType): TryTarget {
  if (type === "OPENAPI") return "http";
  if (type === "ASYNCAPI") return "kafka";
  return "postgres";
}

export function hasTryTarget(environment: EnvironmentResponse, target: TryTarget): boolean {
  if (target === "http") return environment.httpBaseUrl != null;
  if (target === "kafka") return environment.kafka != null;
  return environment.postgres != null;
}

/** The `{param}` names of an OpenAPI path template, in order. */
export function templateParams(path: string): string[] {
  return [...path.matchAll(/\{([^/{}]+)}/g)].map((m) => m[1]);
}

/**
 * A try's failure, in the fixed vocabulary — except the server's own 400/502 details, which
 * name the rule or classify the upstream failure and are shown verbatim (never a raw message).
 */
export function tryErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError && (err.status === 400 || err.status === 502) && err.detail) return err.detail;
  return saveErrorMessage(err, t, {
    forbidden: "tryIt.error.forbidden",
    invalid: "tryIt.error.invalid",
    tooManyRequests: "tryIt.error.tooMany",
    failedStatus: "tryIt.error.failedStatus",
    failed: "tryIt.error.failed",
  });
}

/** Key/value rows the editors hold; blank names are dropped when the request is built. */
export type KeyValueRow = { key: string; value: string };

export function rowsToRecord(rows: readonly KeyValueRow[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const row of rows) if (row.key.trim() !== "") out[row.key.trim()] = row.value;
  return out;
}

export function statusColor(status: number): string {
  if (status < 300) return "teal";
  if (status < 400) return "gray";
  if (status < 500) return "orange";
  return "red";
}
