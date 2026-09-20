import { buildQuery, jsonRequest } from "./http";
import type { components, paths } from "./schema";

export type DeadlineFilter = components["schemas"]["LifecycleDeadline"];
export type LifecycleAttention = components["schemas"]["LifecycleAttention"];

export type LifecycleOverviewFilters = {
  q?: string;
  domainId?: number;
  systemId?: number;
  ownerTeamId?: number;
  ownerUserId?: number;
  types?: readonly components["schemas"]["ContractType"][];
  supportStatuses?: readonly components["schemas"]["SupportStatus"][];
  deadline?: DeadlineFilter;
  attention?: LifecycleAttention;
};

export type LifecycleOverviewRow = components["schemas"]["LifecycleOverviewRow"];
export type LifecycleOverviewPage = paths["/api/v1/contracts/lifecycle-overview"]["get"]["responses"]["200"]["content"]["application/json"];
export type LifecycleOverviewSummary = paths["/api/v1/contracts/lifecycle-overview/summary"]["get"]["responses"]["200"]["content"]["application/json"];

type ListQuery = LifecycleOverviewFilters & { page: number; pageSize: number; sort?: string };

function params(query: LifecycleOverviewFilters) {
  return {
    q: query.q,
    domainId: query.domainId,
    systemId: query.systemId,
    ownerTeamId: query.ownerTeamId,
    ownerUserId: query.ownerUserId,
    type: query.types,
    supportStatus: query.supportStatuses,
    deadline: query.deadline,
    attention: query.attention,
  };
}

export async function listLifecycleOverview(query: ListQuery): Promise<LifecycleOverviewPage> {
  const search = buildQuery({ page: query.page, pageSize: query.pageSize, sort: query.sort, ...params(query) });
  return jsonRequest(`/api/v1/contracts/lifecycle-overview?${search}`);
}

export async function getLifecycleOverviewSummary(query: LifecycleOverviewFilters): Promise<LifecycleOverviewSummary> {
  const search = buildQuery(params(query));
  return jsonRequest(`/api/v1/contracts/lifecycle-overview/summary${search ? `?${search}` : ""}`);
}
