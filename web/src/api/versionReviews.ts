import { buildQuery, jsonRequest } from "./http";
import type { components } from "./schema";

export type VersionReviewPage = components["schemas"]["VersionReviewPage"];
export type VersionReviewResponse = components["schemas"]["VersionReviewResponse"];
export type VersionReviewEntryPage = components["schemas"]["VersionReviewEntryPage"];
export type VersionReviewEntryResponse = components["schemas"]["VersionReviewEntryResponse"];
export type VersionReviewEntryKind = components["schemas"]["VersionReviewEntryKind"];

export async function listVersionReviews(contractId: number, versionId: number, page: number, pageSize: number): Promise<VersionReviewPage> {
  const query = buildQuery({ page, pageSize });
  return jsonRequest<VersionReviewPage>(`/api/v1/contracts/${contractId}/versions/${versionId}/reviews?${query}`);
}

export async function createVersionReview(contractId: number, versionId: number, expectedContentRevision: number): Promise<VersionReviewResponse> {
  return jsonRequest<VersionReviewResponse>(`/api/v1/contracts/${contractId}/versions/${versionId}/reviews`, {
    method: "POST",
    body: JSON.stringify({ expectedContentRevision }),
  });
}

export async function listVersionReviewEntries(reviewId: number, page: number, pageSize: number): Promise<VersionReviewEntryPage> {
  const query = buildQuery({ page, pageSize, sort: "-createdAt,-id" });
  return jsonRequest<VersionReviewEntryPage>(`/api/v1/version-reviews/${reviewId}/entries?${query}`);
}

export async function createVersionReviewEntry(
  reviewId: number,
  body: { expectedContentRevision: number; kind: VersionReviewEntryKind; body?: string },
): Promise<VersionReviewEntryResponse> {
  return jsonRequest<VersionReviewEntryResponse>(`/api/v1/version-reviews/${reviewId}/entries`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}
