import { buildQuery, jsonRequest } from "./http";
import type { components } from "./schema";

export type ReviewInboxPage = components["schemas"]["ReviewInboxPage"];
export type ReviewInboxRow = components["schemas"]["ReviewInboxRow"];
export type ReviewInboxSummary = components["schemas"]["ReviewInboxSummary"];
export type ReviewInboxScope = components["schemas"]["ReviewInboxScope"];
export type ReviewInboxAttention = components["schemas"]["ReviewInboxAttention"];

export type ReviewInboxFilters = {
  q?: string;
  scope: ReviewInboxScope;
  attention?: ReviewInboxAttention;
};

export async function listReviewInbox(filters: ReviewInboxFilters & { page: number; pageSize: number; sort: string }): Promise<ReviewInboxPage> {
  const query = buildQuery({ page: filters.page, pageSize: filters.pageSize, sort: filters.sort, q: filters.q, scope: filters.scope, attention: filters.attention });
  return jsonRequest<ReviewInboxPage>(`/api/v1/version-reviews/inbox?${query}`);
}

export async function getReviewInboxSummary(filters: ReviewInboxFilters): Promise<ReviewInboxSummary> {
  const query = buildQuery({ q: filters.q, scope: filters.scope, attention: filters.attention });
  return jsonRequest<ReviewInboxSummary>(`/api/v1/version-reviews/inbox/summary?${query}`);
}
