// Try-it API — the server calls a real environment on the user's behalf (an HTTP request to an
// OpenAPI operation, a Kafka publish/read, a read-only SQL sample) and answers the observation
// with a CONFORMANCE report. The SPA never touches a target itself.

import { jsonRequest } from "./http";
import type { components, paths } from "./schema";

export type TryCatalog = components["schemas"]["TryCatalogResponse"];
export type HttpOperationSummary = components["schemas"]["HttpOperationSummary"];
export type KafkaChannelSummary = components["schemas"]["KafkaChannelSummary"];
export type SqlDatasetSummary = components["schemas"]["SqlDatasetSummary"];
export type ConformanceReport = components["schemas"]["ConformanceReport"];
export type TryHttpBody = components["schemas"]["TryHttpRequest"];
export type TryHttpResult = components["schemas"]["TryHttpResponse"];
export type TryKafkaPublishBody = components["schemas"]["TryKafkaPublishRequest"];
export type TryKafkaPublishResult = components["schemas"]["TryKafkaPublishResponse"];
export type TryKafkaReadBody = components["schemas"]["TryKafkaReadRequest"];
export type TryKafkaReadResult = components["schemas"]["TryKafkaReadResponse"];
export type KafkaRecordView = components["schemas"]["KafkaRecordView"];
export type TrySqlBody = components["schemas"]["TrySqlRequest"];
export type TrySqlResult = components["schemas"]["TrySqlResponse"];
type TryPaths = keyof paths & `/api/v1/contracts/{id}/versions/{vid}/try${string}`;

function tryPath(contractId: number, versionId: number, leg: TryPaths extends `${string}/try${infer L}` ? L : never) {
  return `/api/v1/contracts/${contractId}/versions/${versionId}/try${leg}`;
}

/** What the stored document offers to try — computed server-side (the SPA parses no YAML). */
export async function getTryCatalog(contractId: number, versionId: number): Promise<TryCatalog> {
  return jsonRequest<TryCatalog>(tryPath(contractId, versionId, ""));
}

export async function tryHttp(contractId: number, versionId: number, body: TryHttpBody): Promise<TryHttpResult> {
  return jsonRequest<TryHttpResult>(tryPath(contractId, versionId, "/http"), { method: "POST", body: JSON.stringify(body) });
}

export async function tryKafkaPublish(contractId: number, versionId: number, body: TryKafkaPublishBody): Promise<TryKafkaPublishResult> {
  return jsonRequest<TryKafkaPublishResult>(tryPath(contractId, versionId, "/kafka/publish"), { method: "POST", body: JSON.stringify(body) });
}

export async function tryKafkaRead(contractId: number, versionId: number, body: TryKafkaReadBody): Promise<TryKafkaReadResult> {
  return jsonRequest<TryKafkaReadResult>(tryPath(contractId, versionId, "/kafka/read"), { method: "POST", body: JSON.stringify(body) });
}

export async function trySql(contractId: number, versionId: number, body: TrySqlBody): Promise<TrySqlResult> {
  return jsonRequest<TrySqlResult>(tryPath(contractId, versionId, "/sql"), { method: "POST", body: JSON.stringify(body) });
}
