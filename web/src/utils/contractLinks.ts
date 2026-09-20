/** The ONE place the contracts route family is spelled out — never hand-assemble a contract URL. */
export const hierarchyPath = "/";
export const contractsPath = "/contracts";
/** Catalog-wide release-line lifecycle overview. */
export const lifecycleOverviewPath = "/lifecycle";
/** Personal/catalog review queue. */
export const reviewInboxPath = "/reviews";
/** The catalog-wide Errors report — a read over the stored check findings, not a sub-route of a contract. */
export const errorsPath = "/errors";
export const newContractPath = `${contractsPath}/new`;
export const importContractPath = `${contractsPath}/import`;
/** A fresh contract's draft — the type picker is on the page itself, so there is no query param here. */
export const inferContractPath = `${contractsPath}/infer`;
export const contractPath = (id: number) => `${contractsPath}/${id}`;
export const editContractPath = (id: number) => `${contractPath(id)}/edit`;
/** An existing contract's next draft — its type and system are already known. */
export const inferVersionPath = (id: number) => `${contractPath(id)}/infer`;
/** `view` = a deep link into the Reader or the Source rendering (the page remembers the user's own choice otherwise). */
export const versionPath = (contractId: number, versionId: number, view?: "reader" | "source") =>
  `${contractPath(contractId)}/versions/${versionId}${view ? `?view=${view}` : ""}`;
export const versionReviewsPath = (contractId: number, versionId: number) => `${versionPath(contractId, versionId)}#reviews`;
/** `from` copies one stored document; `major` keeps creation inside a selected release line. */
export const newVersionPath = (contractId: number, from?: number, major?: number) => {
  const params = new URLSearchParams();
  if (from != null) params.set("from", String(from));
  if (major != null) params.set("major", String(major));
  const query = params.toString();
  return `${contractPath(contractId)}/versions/new${query ? `?${query}` : ""}`;
};
export const versionDiffPath = (contractId: number, from?: number, to?: number) => {
  const params = new URLSearchParams();
  if (from != null) params.set("from", String(from));
  if (to != null) params.set("to", String(to));
  const query = params.toString();
  return `${contractPath(contractId)}/diff${query ? `?${query}` : ""}`;
};
