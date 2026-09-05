// Domains API — the top of the Domain → System → Contract hierarchy, an ADMIN-curated registry
// everyone reads. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type DomainPage = paths["/api/v1/domains"]["get"]["responses"]["200"]["content"]["application/json"];
export type DomainResponse = DomainPage["items"][number];
export type DomainBody = paths["/api/v1/domains"]["post"]["requestBody"]["content"]["application/json"];

type DomainListQuery = { page: number; pageSize: number; sort?: string; name?: string };

export async function listDomains(q: DomainListQuery): Promise<DomainPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name });
  return jsonRequest<DomainPage>(`/api/v1/domains?${params}`);
}

/** Every domain, name-ordered — the pickers' source (the registry is admin-curated and small). */
export async function listAllDomains(): Promise<DomainResponse[]> {
  return (await listDomains({ page: 1, pageSize: 100, sort: "name" })).items;
}

export async function createDomain(body: DomainBody): Promise<DomainResponse> {
  return jsonRequest<DomainResponse>("/api/v1/domains", { method: "POST", body: JSON.stringify(body) });
}

export async function updateDomain(id: number, body: DomainBody): Promise<void> {
  await voidRequest(`/api/v1/domains/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteDomain(id: number): Promise<void> {
  await voidRequest(`/api/v1/domains/${id}`, { method: "DELETE" });
}
