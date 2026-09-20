import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

type ReleaseLinePage = paths["/api/v1/contracts/{id}/release-lines"]["get"]["responses"]["200"]["content"]["application/json"];
export type ReleaseLineResponse = components["schemas"]["ReleaseLineResponse"];
export type ReleaseLineUpdateBody = components["schemas"]["ReleaseLineUpdateRequest"];
export type SupportStatus = components["schemas"]["SupportStatus"];

async function listReleaseLines(contractId: number, page: number, pageSize: number): Promise<ReleaseLinePage> {
  const params = buildQuery({ page, pageSize, sort: "-major" });
  return jsonRequest<ReleaseLinePage>(`/api/v1/contracts/${contractId}/release-lines?${params}`);
}

/** Release lines are compact, but still page until `total` so large catalogs are never truncated. */
export async function listAllReleaseLines(contractId: number): Promise<ReleaseLineResponse[]> {
  const pageSize = 100;
  const items: ReleaseLineResponse[] = [];
  for (let page = 1; ; page += 1) {
    const result = await listReleaseLines(contractId, page, pageSize);
    items.push(...result.items);
    if (items.length >= result.total || result.items.length === 0) return items;
  }
}

export async function updateReleaseLine(contractId: number, major: number, body: ReleaseLineUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/contracts/${contractId}/release-lines/${major}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function getReleaseLine(contractId: number, major: number): Promise<ReleaseLineResponse> {
  return jsonRequest(`/api/v1/contracts/${contractId}/release-lines/${major}`);
}
