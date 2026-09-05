// Environments API — the try-it connection targets of a system (HTTP base URL, Kafka, read-only
// PostgreSQL); ADMIN-curated, everyone reads. Responses never carry a password (`hasPassword`).

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type EnvironmentPage = paths["/api/v1/environments"]["get"]["responses"]["200"]["content"]["application/json"];
export type EnvironmentResponse = EnvironmentPage["items"][number];
export type EnvironmentBody = components["schemas"]["EnvironmentRequest"];
export type KafkaSecurityProtocol = components["schemas"]["KafkaSecurityProtocol"];
export type KafkaSaslMechanism = components["schemas"]["KafkaSaslMechanism"];

type EnvironmentListQuery = { page: number; pageSize: number; sort?: string; name?: string; systemId?: number };

export async function listEnvironments(q: EnvironmentListQuery): Promise<EnvironmentPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name, systemId: q.systemId });
  return jsonRequest<EnvironmentPage>(`/api/v1/environments?${params}`);
}

export async function createEnvironment(body: EnvironmentBody): Promise<EnvironmentResponse> {
  return jsonRequest<EnvironmentResponse>("/api/v1/environments", { method: "POST", body: JSON.stringify(body) });
}

export async function updateEnvironment(id: number, body: EnvironmentBody): Promise<void> {
  await voidRequest(`/api/v1/environments/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteEnvironment(id: number): Promise<void> {
  await voidRequest(`/api/v1/environments/${id}`, { method: "DELETE" });
}
