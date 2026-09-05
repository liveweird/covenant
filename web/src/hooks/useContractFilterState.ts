import { useDebouncedValue } from "@mantine/hooks";
import type { ContractFilters, ContractType, Lifecycle } from "../api/contracts";
import { CONTRACT_TYPES } from "../utils/contractForm";
import { LIFECYCLES } from "../utils/lifecycle";
import { isBoolean, isString, useStoredState } from "./useStoredState";

const isStringArray = (v: unknown): v is string[] => Array.isArray(v) && v.every((x) => typeof x === "string");

/**
 * The contract filter set shared by the Contracts list and the Hierarchy tree (the two
 * endpoints declare the same params): persisted per view under `<viewKey>.filter.*`, the free
 * text debounced. `values` is what the API takes; `deps` resets paging; `activeCount` feeds the
 * FilterPanel badge. Extend here + in ContractFilterControls, never on one page.
 */
export function useContractFilterState(viewKey: string) {
  const [q, setQ] = useStoredState(`${viewKey}.filter.q`, "", isString);
  const [domainId, setDomainId] = useStoredState(`${viewKey}.filter.domain`, "", isString);
  const [systemId, setSystemId] = useStoredState(`${viewKey}.filter.system`, "", isString);
  const [types, setTypes] = useStoredState<string[]>(`${viewKey}.filter.types`, [], isStringArray);
  const [lifecycles, setLifecycles] = useStoredState<string[]>(`${viewKey}.filter.lifecycles`, [], isStringArray);
  const [ownerTeamId, setOwnerTeamId] = useStoredState(`${viewKey}.filter.ownerTeam`, "", isString);
  const [hasErrors, setHasErrors] = useStoredState(`${viewKey}.filter.hasErrors`, false, isBoolean);
  const [debouncedQ] = useDebouncedValue(q.trim(), 300);

  // A stale stored value outside today's enums is dropped silently (the usePagedSort rule).
  const validTypes = types.filter((t): t is ContractType => (CONTRACT_TYPES as readonly string[]).includes(t));
  const validLifecycles = lifecycles.filter((l): l is Lifecycle => (LIFECYCLES as readonly string[]).includes(l));

  const values: ContractFilters = {
    q: debouncedQ || undefined,
    domainId: domainId ? Number(domainId) : undefined,
    systemId: systemId ? Number(systemId) : undefined,
    types: validTypes,
    lifecycles: validLifecycles,
    ownerTeamId: ownerTeamId ? Number(ownerTeamId) : undefined,
    hasErrors: hasErrors || undefined,
  };
  const activeCount =
    (debouncedQ ? 1 : 0) + (domainId ? 1 : 0) + (systemId ? 1 : 0) + (validTypes.length > 0 ? 1 : 0) +
    (validLifecycles.length > 0 ? 1 : 0) + (ownerTeamId ? 1 : 0) + (hasErrors ? 1 : 0);

  return {
    values,
    deps: [debouncedQ, domainId, systemId, validTypes.join(","), validLifecycles.join(","), ownerTeamId, hasErrors],
    activeCount,
    slots: {
      q, setQ,
      domainId, setDomainId,
      systemId, setSystemId: (v: string) => setSystemId(v),
      types: validTypes as string[], setTypes,
      lifecycles: validLifecycles as string[], setLifecycles,
      ownerTeamId, setOwnerTeamId,
      hasErrors, setHasErrors,
    },
  };
}

export type ContractFilterState = ReturnType<typeof useContractFilterState>;
