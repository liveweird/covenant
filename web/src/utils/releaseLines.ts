import type { TFunction } from "i18next";
import type { SupportStatus } from "../api/releaseLines";

/** Support statuses in the policy editor's display order. */
export const SUPPORT_STATUSES = ["UNSPECIFIED", "SUPPORTED", "MAINTENANCE", "END_OF_LIFE"] as const satisfies readonly SupportStatus[];

/** A localized known status, with the raw server value retained for forward compatibility. */
export function supportStatusLabel(value: string | undefined, t: TFunction): string {
  const known = SUPPORT_STATUSES.find((status) => status === value);
  return known ? t(`contracts.releaseLines.status.${known}`) : (value ?? "");
}
