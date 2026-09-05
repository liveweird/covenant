// Contract versions API — the documents themselves (raw YAML/JSON text, byte-exact), their
// lifecycle, the stored check report, and the live check the editor runs while typing.

import { ApiError, authedFetch, buildQuery, jsonRequest, safeJson, voidRequest } from "./http";
import type { components, paths } from "./schema";

export type VersionPage = paths["/api/v1/contracts/{id}/versions"]["get"]["responses"]["200"]["content"]["application/json"];
export type VersionListItem = VersionPage["items"][number];
export type VersionResponse = components["schemas"]["VersionResponse"];
export type Finding = components["schemas"]["Finding"];
export type Severity = components["schemas"]["Severity"];
export type FindingSource = components["schemas"]["FindingSource"];
export type CheckReport = components["schemas"]["CheckReport"];
export type DocumentCheckBody = components["schemas"]["DocumentCheckRequest"];
export type DocumentFormat = components["schemas"]["DocumentFormat"];
export type SyncState = components["schemas"]["SyncStateResponse"];
// The reader's render model (milestone 4) — computed server-side, typed here from the spec.
export type RenderModel = components["schemas"]["RenderModelResponse"];
export type SchemaNode = components["schemas"]["SchemaNode"];
export type OpenApiModel = components["schemas"]["OpenApiModel"];
export type OperationView = components["schemas"]["OperationView"];
export type ResponseView = components["schemas"]["ResponseView"];
export type MediaTypeView = components["schemas"]["MediaTypeView"];
export type SecurityRequirementView = components["schemas"]["SecurityRequirementView"];
export type SecuritySchemeView = components["schemas"]["SecuritySchemeView"];
export type NamedSchemaView = components["schemas"]["NamedSchemaView"];
export type InfoView = components["schemas"]["InfoView"];
export type TagView = components["schemas"]["TagView"];
export type ExternalDocsView = components["schemas"]["ExternalDocsView"];
export type ExampleView = components["schemas"]["ExampleView"];
export type KeyValue = components["schemas"]["KeyValue"];
export type AsyncApiModel = components["schemas"]["AsyncApiModel"];
export type ChannelView = components["schemas"]["ChannelView"];
export type AsyncOperationView = components["schemas"]["AsyncOperationView"];
export type MessageView = components["schemas"]["MessageView"];
export type OdcsModel = components["schemas"]["OdcsModel"];
export type DatasetView = components["schemas"]["DatasetView"];
export type OdcsPropertyView = components["schemas"]["OdcsPropertyView"];
export type QualityView = components["schemas"]["QualityView"];
type Lifecycle = components["schemas"]["Lifecycle"];

export type SaveOptions = { allowInvalid?: boolean };

type VersionListQuery = { page: number; pageSize: number; sort?: string; lifecycle?: Lifecycle };

function withWaiver(path: string, options?: SaveOptions): string {
  return options?.allowInvalid ? `${path}?allowInvalid=true` : path;
}

export async function listVersions(contractId: number, q: VersionListQuery): Promise<VersionPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, lifecycle: q.lifecycle });
  return jsonRequest<VersionPage>(`/api/v1/contracts/${contractId}/versions?${params}`);
}

export async function getVersion(contractId: number, versionId: number): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(`/api/v1/contracts/${contractId}/versions/${versionId}`);
}

/** The raw stored bytes — the download path (the viewer reads `content` off the detail instead). */
/** The reader's render model — keyed by the version's content hash upstream, so a stored edit refetches it. */
export async function getVersionModel(contractId: number, versionId: number): Promise<RenderModel> {
  return jsonRequest<RenderModel>(`/api/v1/contracts/${contractId}/versions/${versionId}/model`);
}

export async function getVersionContent(contractId: number, versionId: number): Promise<string> {
  const res = await authedFetch(`/api/v1/contracts/${contractId}/versions/${versionId}/content`);
  if (!res.ok) throw new ApiError(res.status, await safeJson(res));
  return res.text();
}

export async function createVersion(
  contractId: number,
  body: { version: string; content: string; sourceUrl?: string | null },
  options?: SaveOptions,
): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(withWaiver(`/api/v1/contracts/${contractId}/versions`, options), {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export async function updateVersionContent(contractId: number, versionId: number, content: string, options?: SaveOptions): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(withWaiver(`/api/v1/contracts/${contractId}/versions/${versionId}/content`, options), {
    method: "PUT",
    body: JSON.stringify({ content }),
  });
}

export async function transitionVersion(contractId: number, versionId: number, to: Lifecycle): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(`/api/v1/contracts/${contractId}/versions/${versionId}/transition`, {
    method: "POST",
    body: JSON.stringify({ to }),
  });
}

export async function recheckVersion(contractId: number, versionId: number): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(`/api/v1/contracts/${contractId}/versions/${versionId}/recheck`, { method: "POST" });
}

export async function deleteVersion(contractId: number, versionId: number): Promise<void> {
  await voidRequest(`/api/v1/contracts/${contractId}/versions/${versionId}`, { method: "DELETE" });
}

/** The version's repo reference plus the baseline text the sync modal attributes a difference with. */
export async function getSyncState(contractId: number, versionId: number): Promise<SyncState> {
  return jsonRequest<SyncState>(`/api/v1/contracts/${contractId}/versions/${versionId}/sync`);
}

/** Set or clear (null) the version's repo reference — no fetch happens; a changed reference resets the sync state. */
export async function setVersionSource(contractId: number, versionId: number, sourceUrl: string | null): Promise<void> {
  await voidRequest(`/api/v1/contracts/${contractId}/versions/${versionId}/source`, { method: "PUT", body: JSON.stringify({ sourceUrl }) });
}

/**
 * The repo → Covenant overwrite with the copy the client fetched: the server waives soft findings
 * itself (the import posture — no Save-anyway here), a HARD finding or a locked lifecycle rejects.
 */
export async function syncVersion(contractId: number, versionId: number, content: string): Promise<VersionResponse> {
  return jsonRequest<VersionResponse>(`/api/v1/contracts/${contractId}/versions/${versionId}/sync`, { method: "POST", body: JSON.stringify({ content }) });
}

/** The live check — findings-so-far for an in-progress document; never a 400 for document problems. */
export async function checkDocument(body: DocumentCheckBody): Promise<CheckReport> {
  return jsonRequest<CheckReport>("/api/v1/contracts/versions/check", { method: "POST", body: JSON.stringify(body) });
}

/** A HARD finding (unparseable text, wrong document type) — never waivable, always a 400. */
export function isHardFinding(f: Finding): boolean {
  return f.source === "SYNTAX";
}

/** A SOFT error — blocks a strict save, waived by `allowInvalid=true` (the Save-anyway flow). */
function isSoftError(f: Finding): boolean {
  return f.severity === "ERROR" && !isHardFinding(f);
}

/**
 * The Save-anyway trigger: a strict save's 400 whose detail names blocking findings. The
 * problem body carries only the summary, so the SPA re-runs the live check to list the soft
 * errors for the modal; any other failure (a HARD rejection, a SemVer rule, a 403/409) is null
 * and renders through the page's fixed vocabulary.
 */
export async function softRejectionFindings(err: unknown, document: DocumentCheckBody): Promise<Finding[] | null> {
  if (!(err instanceof ApiError) || err.status !== 400) return null;
  if (!(err.detail?.includes("blocking finding") ?? false)) return null;
  const report = await checkDocument(document);
  const soft = report.findings.filter(isSoftError);
  return soft.length > 0 ? soft : null;
}
