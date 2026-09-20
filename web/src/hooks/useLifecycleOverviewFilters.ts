import { useDebouncedValue } from "@mantine/hooks";
import type { ContractType } from "../api/contracts";
import type { DeadlineFilter, LifecycleAttention, LifecycleOverviewFilters } from "../api/lifecycleOverview";
import type { SupportStatus } from "../api/releaseLines";
import { CONTRACT_TYPES } from "../utils/contractForm";
import { SUPPORT_STATUSES } from "../utils/releaseLines";
import { isOneOfOrNull, isString, useStoredState } from "./useStoredState";

const DEADLINE_FILTERS = ["REACHED", "NEXT_30_DAYS", "NONE"] as const satisfies readonly DeadlineFilter[];
const ATTENTION_FILTERS = ["DEADLINE_SOON", "SUPPORT_ENDED", "MIGRATION_INCOMPLETE", "USAGE_UNCERTAIN"] as const satisfies readonly LifecycleAttention[];
const isStringArray = (value: unknown): value is string[] => Array.isArray(value) && value.every((item) => typeof item === "string");

export function useLifecycleOverviewFilters(viewKey: string) {
  const [q, setQ] = useStoredState(`${viewKey}.filter.q`, "", isString);
  const [domainId, setDomainId] = useStoredState(`${viewKey}.filter.domain`, "", isString);
  const [systemId, setSystemId] = useStoredState(`${viewKey}.filter.system`, "", isString);
  const [types, setTypes] = useStoredState<string[]>(`${viewKey}.filter.types`, [], isStringArray);
  const [owner, setOwner] = useStoredState(`${viewKey}.filter.owner`, "", isString);
  const [supportStatuses, setSupportStatuses] = useStoredState<string[]>(`${viewKey}.filter.supportStatuses`, [], isStringArray);
  const [deadline, setDeadline] = useStoredState<DeadlineFilter | null>(`${viewKey}.filter.deadline`, null, isOneOfOrNull(DEADLINE_FILTERS));
  const [attention, setAttention] = useStoredState<LifecycleAttention | null>(`${viewKey}.filter.attention`, null, isOneOfOrNull(ATTENTION_FILTERS));
  const [debouncedQ] = useDebouncedValue(q.trim(), 300);
  const validTypes = types.filter((value): value is ContractType => (CONTRACT_TYPES as readonly string[]).includes(value));
  const validStatuses = supportStatuses.filter((value): value is SupportStatus => (SUPPORT_STATUSES as readonly string[]).includes(value));
  const [ownerKind, ownerId] = owner.split(":");
  const numericOwnerId = Number(ownerId);

  const values: LifecycleOverviewFilters = {
    q: debouncedQ || undefined,
    domainId: domainId ? Number(domainId) : undefined,
    systemId: systemId ? Number(systemId) : undefined,
    types: validTypes,
    ownerTeamId: ownerKind === "TEAM" && Number.isFinite(numericOwnerId) ? numericOwnerId : undefined,
    ownerUserId: ownerKind === "USER" && Number.isFinite(numericOwnerId) ? numericOwnerId : undefined,
    supportStatuses: validStatuses,
    deadline: deadline ?? undefined,
    attention: attention ?? undefined,
  };
  const deps = [debouncedQ, domainId, systemId, validTypes.join(","), owner, validStatuses.join(","), deadline, attention];
  const activeCount = deps.filter((value) => value != null && value !== "").length;

  return {
    values,
    deps,
    activeCount,
    slots: {
      q, setQ,
      domainId, setDomainId,
      systemId, setSystemId,
      types: validTypes as string[], setTypes,
      owner, setOwner,
      supportStatuses: validStatuses as string[], setSupportStatuses,
      deadline, setDeadline,
      attention, setAttention,
    },
  };
}

export type LifecycleOverviewFilterState = ReturnType<typeof useLifecycleOverviewFilters>;
