import { useQuery } from "@tanstack/react-query";
import type { ContractResponse } from "../api/contracts";
import { getVersionModel, type VersionResponse } from "../api/versions";

/**
 * The reader's render model — keyed by the version's content hash upstream, so a stored edit
 * refetches it. Shared by `ContractReader` and the version page's side panel table of contents
 * (TanStack dedupes the two consumers to one fetch). `contract`/`version` may be absent while the
 * page is still loading its own queries — the model query then simply stays disabled rather than
 * forcing every caller to skip the hook (which React's rules of hooks forbid). `enabled` lets the
 * page hold the fetch back too: the model is a server-side `$ref` walk the Source view never
 * needs, so the page asks for it only while the Reader view is showing.
 */
export function useVersionModel(
  contract: ContractResponse | null | undefined,
  version: VersionResponse | null | undefined,
  options: { enabled?: boolean } = {},
) {
  const { enabled = true } = options;
  return useQuery({
    queryKey: ["contracts", "version", contract?.id, version?.id, "model", version?.contentSha256],
    queryFn: () => getVersionModel(contract!.id, version!.id),
    enabled: enabled && contract != null && version != null,
  });
}
