/** The ONE place the contracts route family is spelled out — never hand-assemble a contract URL. */
export const hierarchyPath = "/";
export const contractsPath = "/contracts";
export const newContractPath = `${contractsPath}/new`;
export const importContractPath = `${contractsPath}/import`;
export const contractPath = (id: number) => `${contractsPath}/${id}`;
export const editContractPath = (id: number) => `${contractPath(id)}/edit`;
/** `view` = a deep link into the Reader or the Source rendering (the page remembers the user's own choice otherwise). */
export const versionPath = (contractId: number, versionId: number, view?: "reader" | "source") =>
  `${contractPath(contractId)}/versions/${versionId}${view ? `?view=${view}` : ""}`;
/** `from` = the version to start the new document from (copy its text); omitted = a blank template. */
export const newVersionPath = (contractId: number, from?: number) =>
  `${contractPath(contractId)}/versions/new${from == null ? "" : `?from=${from}`}`;
export const versionDiffPath = (contractId: number, from?: number, to?: number) => {
  const params = new URLSearchParams();
  if (from != null) params.set("from", String(from));
  if (to != null) params.set("to", String(to));
  const query = params.toString();
  return `${contractPath(contractId)}/diff${query ? `?${query}` : ""}`;
};
