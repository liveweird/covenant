// Systems API — the applications exposing contracts, each inside one domain; ADMIN-curated,
// everyone reads. Thin endpoint wrappers: transport in ./http, types from ./schema.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type SystemPage = paths["/api/v1/systems"]["get"]["responses"]["200"]["content"]["application/json"];
export type SystemResponse = SystemPage["items"][number];
export type SystemBody = paths["/api/v1/systems"]["post"]["requestBody"]["content"]["application/json"];

type SystemListQuery = { page: number; pageSize: number; sort?: string; name?: string; domainId?: number };

export async function listSystems(q: SystemListQuery): Promise<SystemPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, name: q.name, domainId: q.domainId });
  return jsonRequest<SystemPage>(`/api/v1/systems?${params}`);
}

export async function createSystem(body: SystemBody): Promise<SystemResponse> {
  return jsonRequest<SystemResponse>("/api/v1/systems", { method: "POST", body: JSON.stringify(body) });
}

export async function updateSystem(id: number, body: SystemBody): Promise<void> {
  await voidRequest(`/api/v1/systems/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteSystem(id: number): Promise<void> {
  await voidRequest(`/api/v1/systems/${id}`, { method: "DELETE" });
}
