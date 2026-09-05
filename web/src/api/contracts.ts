// Contracts API — the catalog's records (Domain → System → Contract): everyone reads, the
// owning team's members / the owning user / ADMIN write, ADMIN transfers ownership. Thin
// endpoint wrappers: transport in ./http, types from ./schema. Versions live in ./versions.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type ContractPage = paths["/api/v1/contracts"]["get"]["responses"]["200"]["content"]["application/json"];
export type ContractResponse = ContractPage["items"][number];
export type ContractCreateBody = paths["/api/v1/contracts"]["post"]["requestBody"]["content"]["application/json"];
export type ContractUpdateBody = paths["/api/v1/contracts/{id}"]["put"]["requestBody"]["content"]["application/json"];
export type OwnerUpdateBody = paths["/api/v1/contracts/{id}/owner"]["put"]["requestBody"]["content"]["application/json"];
export type ContractType = components["schemas"]["ContractType"];
export type Lifecycle = components["schemas"]["Lifecycle"];
export type OwnerRef = components["schemas"]["OwnerRef"];
export type TreeResponse = paths["/api/v1/contracts/tree"]["get"]["responses"]["200"]["content"]["application/json"];
export type TreeDomain = TreeResponse["domains"][number];
export type TreeSystem = TreeDomain["systems"][number];
export type TreeContract = TreeSystem["contracts"][number];
export type ImportItem = components["schemas"]["ImportItem"];
export type ImportItemResult = components["schemas"]["ImportItemResult"];
export type ImportStatus = components["schemas"]["ImportStatus"];
type ImportResponse = components["schemas"]["ImportResponse"];
type FetchUrlResponse = components["schemas"]["FetchUrlResponse"];
export type ContractExportResponse = components["schemas"]["ContractExportResponse"];

/** The list's and the tree's shared filter set — `type`/`lifecycle` repeat as the server's IN idiom. */
export type ContractFilters = {
  q?: string;
  domainId?: number;
  systemId?: number;
  types?: readonly ContractType[];
  lifecycles?: readonly Lifecycle[];
  ownerTeamId?: number;
  ownerUserId?: number;
  hasErrors?: boolean;
};

type ContractListQuery = ContractFilters & { page: number; pageSize: number; sort?: string };

function filterParams(f: ContractFilters) {
  return {
    q: f.q,
    domainId: f.domainId,
    systemId: f.systemId,
    type: f.types,
    lifecycle: f.lifecycles,
    ownerTeamId: f.ownerTeamId,
    ownerUserId: f.ownerUserId,
    // An omit-when-false param: the list means "only flawed" with true and "everything" without.
    hasErrors: f.hasErrors || undefined,
  };
}

export async function listContracts(q: ContractListQuery): Promise<ContractPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, ...filterParams(q) });
  return jsonRequest<ContractPage>(`/api/v1/contracts?${params}`);
}

export async function getContractTree(f: ContractFilters = {}): Promise<TreeResponse> {
  const params = buildQuery(filterParams(f));
  return jsonRequest<TreeResponse>(`/api/v1/contracts/tree${params ? `?${params}` : ""}`);
}

export async function getContract(id: number): Promise<ContractResponse> {
  return jsonRequest<ContractResponse>(`/api/v1/contracts/${id}`);
}

export async function createContract(body: ContractCreateBody): Promise<ContractResponse> {
  return jsonRequest<ContractResponse>("/api/v1/contracts", { method: "POST", body: JSON.stringify(body) });
}

export async function updateContract(id: number, body: ContractUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/contracts/${id}`, { method: "PUT", body: JSON.stringify(body) });
}

export async function transferContractOwner(id: number, body: OwnerUpdateBody): Promise<void> {
  await voidRequest(`/api/v1/contracts/${id}/owner`, { method: "PUT", body: JSON.stringify(body) });
}

export async function deleteContract(id: number): Promise<void> {
  await voidRequest(`/api/v1/contracts/${id}`, { method: "DELETE" });
}

export async function exportContract(id: number): Promise<ContractExportResponse> {
  return jsonRequest<ContractExportResponse>(`/api/v1/contracts/${id}/export`);
}

/** The import proper — every stored row is a new version (a new contract when the name is new). */
export async function importContracts(items: ImportItem[]): Promise<ImportItemResult[]> {
  const response = await jsonRequest<ImportResponse>("/api/v1/contracts/import", { method: "POST", body: JSON.stringify({ items }) });
  return response.results;
}

/** The dry run: the same per-row report, nothing stored. */
export async function checkImportContracts(items: ImportItem[]): Promise<ImportItemResult[]> {
  const response = await jsonRequest<ImportResponse>("/api/v1/contracts/import/check", { method: "POST", body: JSON.stringify({ items }) });
  return response.results;
}

export type ContractEventPage = paths["/api/v1/contracts/{id}/events"]["get"]["responses"]["200"]["content"]["application/json"];
export type ContractEvent = ContractEventPage["items"][number];

/** The contract's structural history — paged, newest first (the server's default sort; never re-sorted here). */
export async function listContractEvents(id: number, page: number, pageSize: number): Promise<ContractEventPage> {
  const params = buildQuery({ page, pageSize });
  return jsonRequest<ContractEventPage>(`/api/v1/contracts/${id}/events?${params}`);
}

/** The server-side fetch of a public document URL (https only, no private hosts — the server guards). */
export async function fetchContractUrl(url: string): Promise<string> {
  const response = await jsonRequest<FetchUrlResponse>("/api/v1/contracts/fetch", { method: "POST", body: JSON.stringify({ url }) });
  return response.content;
}
