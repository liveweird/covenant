import type { TFunction } from "i18next";
import type { ContractCreateBody, ContractResponse, ContractType } from "../api/contracts";
import { ApiError, isTimeoutError } from "../api/http";
import type { SystemResponse } from "../api/systems";

// Server limits (contracts/Contract.kt) mirrored client-side.
export const MAX_CONTRACT_NAME_LENGTH = 100;
export const MAX_CONTRACT_DESCRIPTION_LENGTH = 2000;

/** The types in display order, with the human names the badges and pickers show. */
export const CONTRACT_TYPES = ["OPENAPI", "ASYNCAPI", "ODCS"] as const satisfies readonly ContractType[];
export const CONTRACT_TYPE_LABEL: Record<ContractType, string> = {
  OPENAPI: "OpenAPI",
  ASYNCAPI: "AsyncAPI",
  ODCS: "ODCS",
};

/** The owner picker's one value: `TEAM:<id>` / `USER:<id>` — the server's XOR spelled as one Select. */
export type OwnerValue = `TEAM:${number}` | `USER:${number}`;

export const ownerValueOf = (owner: { kind: "TEAM" | "USER"; id: number }): OwnerValue => `${owner.kind}:${owner.id}`;

export function splitOwnerValue(value: string): { ownerTeamId: number | null; ownerUserId: number | null } {
  const [kind, id] = value.split(":");
  const numeric = Number(id);
  if (kind === "TEAM" && Number.isFinite(numeric)) return { ownerTeamId: numeric, ownerUserId: null };
  if (kind === "USER" && Number.isFinite(numeric)) return { ownerTeamId: null, ownerUserId: numeric };
  return { ownerTeamId: null, ownerUserId: null };
}

export type ContractFormValues = {
  systemId: string | null;
  type: ContractType;
  name: string;
  description: string;
  owner: string | null;
};

export const EMPTY_CONTRACT_FORM: ContractFormValues = { systemId: null, type: "OPENAPI", name: "", description: "", owner: null };

export function contractFormValidation(t: TFunction, { withOwner = true, withSystem = true } = {}) {
  return {
    systemId: (value: string | null) => (!withSystem || value ? null : t("contracts.validation.systemRequired")),
    name: (value: string) => {
      const v = value.trim();
      return v.length >= 1 && v.length <= MAX_CONTRACT_NAME_LENGTH ? null : t("contracts.validation.nameLength");
    },
    description: (value: string) =>
      value.trim().length <= MAX_CONTRACT_DESCRIPTION_LENGTH ? null : t("contracts.validation.descriptionLength"),
    owner: (value: string | null) => (!withOwner || value ? null : t("contracts.validation.ownerRequired")),
  };
}

/** A blank description travels as null (the server stores NULL, never ""). */
export function contractDescription(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

export function toContractCreateRequest(values: ContractFormValues): ContractCreateBody {
  return {
    systemId: Number(values.systemId),
    type: values.type,
    name: values.name.trim(),
    description: contractDescription(values.description),
    ...splitOwnerValue(values.owner ?? ""),
  };
}

export function fromContractResponse(contract: ContractResponse): ContractFormValues {
  return {
    systemId: String(contract.system.id),
    type: contract.type,
    name: contract.name,
    description: contract.description ?? "",
    owner: ownerValueOf(contract.owner),
  };
}

/** The contract save vocabulary — 409 is about the name field and is handled by the caller. */
export function contractSaveErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return t("contracts.saveForbidden");
    if (err.status === 404) return t("contracts.saveGone");
    if (err.status === 400) return t("contracts.saveInvalid");
    return t("common.error.saveFailedStatus", { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.saveFailedNetwork");
}

/** Systems grouped under their domains — the picker mirrors the hierarchy (a Select's grouped data). */
export function systemOptions(systems: readonly SystemResponse[]): { group: string; items: { value: string; label: string }[] }[] {
  const byDomain = new Map<string, { value: string; label: string }[]>();
  for (const system of systems) {
    const items = byDomain.get(system.domainName) ?? [];
    items.push({ value: String(system.id), label: system.name });
    byDomain.set(system.domainName, items);
  }
  return [...byDomain.entries()].sort(([a], [b]) => a.localeCompare(b)).map(([group, items]) => ({ group, items }));
}
