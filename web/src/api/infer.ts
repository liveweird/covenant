// Infer API — a DRAFT document from real samples (an HTTP exchange, an AsyncAPI payload batch, an
// ODCS relation description or its rows): `POST /contracts/infer` is pure (nothing stored, no
// audit). The three observe legs pull ONE sample through an Environment with the try-it legs'
// code and trust boundary — they audit like try-it but store nothing either. The SPA never
// reaches a target itself.

import { jsonRequest } from "./http";
import type { components } from "./schema";

export type InferRequest = components["schemas"]["InferRequest"];
export type HttpExchangeSample = components["schemas"]["HttpExchangeSample"];
export type MessageBatchSample = components["schemas"]["MessageBatchSample"];
export type RelationSample = components["schemas"]["RelationSample"];
export type InferResponse = components["schemas"]["InferResponse"];
export type ObserveHttpRequest = components["schemas"]["ObserveHttpRequest"];
export type ObserveHttpResponse = components["schemas"]["ObserveHttpResponse"];
export type ObserveKafkaRequest = components["schemas"]["ObserveKafkaRequest"];
export type ObserveKafkaResponse = components["schemas"]["ObserveKafkaResponse"];
export type ObserveSqlRequest = components["schemas"]["ObserveSqlRequest"];
export type ObserveSqlResponse = components["schemas"]["ObserveSqlResponse"];
export type ObserveRelationsRequest = components["schemas"]["ObserveRelationsRequest"];
export type RelationListResponse = components["schemas"]["RelationListResponse"];

/** Samples in, a draft document out — never stored, never audited (like the try catalog read). */
export async function inferDocument(body: InferRequest): Promise<InferResponse> {
  return jsonRequest<InferResponse>("/api/v1/contracts/infer", { method: "POST", body: JSON.stringify(body) });
}

export async function observeHttp(body: ObserveHttpRequest): Promise<ObserveHttpResponse> {
  return jsonRequest<ObserveHttpResponse>("/api/v1/contracts/infer/observe/http", { method: "POST", body: JSON.stringify(body) });
}

export async function observeKafka(body: ObserveKafkaRequest): Promise<ObserveKafkaResponse> {
  return jsonRequest<ObserveKafkaResponse>("/api/v1/contracts/infer/observe/kafka", { method: "POST", body: JSON.stringify(body) });
}

export async function observeSql(body: ObserveSqlRequest): Promise<ObserveSqlResponse> {
  return jsonRequest<ObserveSqlResponse>("/api/v1/contracts/infer/observe/sql", { method: "POST", body: JSON.stringify(body) });
}

export async function listObservableRelations(body: ObserveRelationsRequest): Promise<RelationListResponse> {
  return jsonRequest<RelationListResponse>("/api/v1/contracts/infer/observe/sql/relations", { method: "POST", body: JSON.stringify(body) });
}
