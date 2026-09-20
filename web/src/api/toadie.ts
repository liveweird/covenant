// Toadie usage integration: ADMIN-curated connections, cached API choices, and a contract's
// declared provider/consumer usage. The browser never contacts Toadie directly.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type ToadieConnectionPage = paths["/api/v1/toadie-connections"]["get"]["responses"]["200"]["content"]["application/json"];
export type ToadieConnection = ToadieConnectionPage["items"][number];
export type ToadieConnectionBody = components["schemas"]["ToadieConnectionRequest"];
type ToadieConnectionCreateBody = components["schemas"]["ToadieConnectionCreateRequest"];
export type ToadieEntityPage = paths["/api/v1/toadie-connections/{id}/apis"]["get"]["responses"]["200"]["content"]["application/json"];
export type ToadieEntityRef = ToadieEntityPage["items"][number];
export type ToadieLinks = paths["/api/v1/contracts/{contractId}/toadie-links"]["get"]["responses"]["200"]["content"]["application/json"];
export type ToadieLinksBody = paths["/api/v1/contracts/{contractId}/toadie-links"]["put"]["requestBody"]["content"]["application/json"];
export type ToadieUsage = paths["/api/v1/contracts/{contractId}/toadie-usage"]["get"]["responses"]["200"]["content"]["application/json"];

export async function listToadieConnections(q: { page: number; pageSize: number; sort?: string }): Promise<ToadieConnectionPage> {
  const params = buildQuery(q);
  return jsonRequest<ToadieConnectionPage>(`/api/v1/toadie-connections?${params}`);
}

export async function createToadieConnection(body: ToadieConnectionCreateBody): Promise<ToadieConnection> {
  return jsonRequest<ToadieConnection>("/api/v1/toadie-connections", { method: "POST", body: JSON.stringify(body) });
}

export async function updateToadieConnection(id: number, body: ToadieConnectionBody): Promise<void> {
  await voidRequest(`/api/v1/toadie-connections/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteToadieConnection(id: number): Promise<void> {
  await voidRequest(`/api/v1/toadie-connections/${id}`, { method: "DELETE" });
}

export async function refreshToadieConnection(id: number): Promise<void> {
  await voidRequest(`/api/v1/toadie-connections/${id}/refresh`, { method: "POST" });
}

export async function listToadieApis(id: number, q: { page: number; pageSize: number; q?: string }): Promise<ToadieEntityPage> {
  const params = buildQuery(q);
  return jsonRequest<ToadieEntityPage>(`/api/v1/toadie-connections/${id}/apis?${params}`);
}

export async function getContractToadieLinks(contractId: number): Promise<ToadieLinks> {
  return jsonRequest<ToadieLinks>(`/api/v1/contracts/${contractId}/toadie-links`);
}

export async function updateContractToadieLinks(contractId: number, body: ToadieLinksBody): Promise<void> {
  await voidRequest(`/api/v1/contracts/${contractId}/toadie-links`, { method: "PUT", body: JSON.stringify(body) });
}

export async function getContractToadieUsage(contractId: number, q: { page: number; pageSize: number; q?: string; role?: "PROVIDER" | "CONSUMER"; sort?: string }): Promise<ToadieUsage> {
  const params = buildQuery(q);
  return jsonRequest<ToadieUsage>(`/api/v1/contracts/${contractId}/toadie-usage?${params}`);
}

export async function refreshContractToadieUsage(contractId: number): Promise<void> {
  await voidRequest(`/api/v1/contracts/${contractId}/toadie-usage/refresh`, { method: "POST" });
}
