// The Errors report API — a catalog-wide read over the stored check findings: a paged row
// per version carrying at least one matching finding, plus server-side facet counts. Everyone
// reads; the filter set is the contracts list's scope (q/domain/system/type/owner) plus the
// version's own lifecycle, severity and source (all repeatable any-of, empty = any).

import { buildQuery, jsonRequest } from "./http";
import { contractScopeParams, type ContractScope } from "./contracts";
import type { FindingSource, Severity } from "./versions";
import type { components, paths } from "./schema";

type Lifecycle = components["schemas"]["Lifecycle"];

export type ErrorPage = paths["/api/v1/contracts/errors"]["get"]["responses"]["200"]["content"]["application/json"];
export type ErrorRow = ErrorPage["items"][number];
export type ErrorFacets = components["schemas"]["ErrorFacetsResponse"];

/** The list's/facets' shared filter set: the contracts scope plus the VERSION's own lifecycle/severity/source. */
export type ErrorFilters = ContractScope & {
  lifecycles?: readonly Lifecycle[];
  severities?: readonly Severity[];
  sources?: readonly FindingSource[];
};

type ErrorListQuery = ErrorFilters & { page: number; pageSize: number; sort?: string };

function errorFilterParams(f: ErrorFilters) {
  return {
    ...contractScopeParams(f),
    lifecycle: f.lifecycles,
    severity: f.severities,
    source: f.sources,
  };
}

export async function listContractErrors(q: ErrorListQuery): Promise<ErrorPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, ...errorFilterParams(q) });
  return jsonRequest<ErrorPage>(`/api/v1/contracts/errors?${params}`);
}

/** The facet counts behind the summary strip's chips — the same filters, each dimension lifted for its own count. */
export async function getContractErrorFacets(f: ErrorFilters): Promise<ErrorFacets> {
  const params = buildQuery(errorFilterParams(f));
  return jsonRequest<ErrorFacets>(`/api/v1/contracts/errors/facets${params ? `?${params}` : ""}`);
}
