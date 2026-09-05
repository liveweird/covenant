import { useTranslation } from "react-i18next";
import { MultiSelect, Select, Switch } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { listAllDomains } from "../api/domains";
import { listSystems } from "../api/systems";
import { listTeams } from "../api/teams";
import type { ContractFacets } from "../api/contracts";
import type { ContractFilterState } from "../hooks/useContractFilterState";
import { CONTRACT_TYPE_LABEL, CONTRACT_TYPES } from "../utils/contractForm";
import { LIFECYCLES } from "../utils/lifecycle";
import ClearableTextInput from "./ClearableTextInput";

function countsByKey<T>(rows: readonly T[] | undefined, key: (row: T) => string, count: (row: T) => number): ReadonlyMap<string, number> | null {
  return rows ? new Map(rows.map((row) => [key(row), count(row)])) : null;
}

/**
 * The controls for useContractFilterState, rendered inside a FilterPanel by the list and the tree.
 * With `facets` (the server's counts over the CURRENT filters, each dimension lifted) every option
 * says how many contracts picking it would yield.
 */
export default function ContractFilterControls({ filters, facets = null }: { filters: ContractFilterState; facets?: ContractFacets | null }) {
  const { t } = useTranslation();
  const { slots } = filters;
  const typeCounts = countsByKey(facets?.type, (f) => f.value, (f) => f.count);
  const lifecycleCounts = countsByKey(facets?.lifecycle, (f) => f.value, (f) => f.count);
  const domainCounts = countsByKey(facets?.domain, (f) => String(f.id), (f) => f.count);
  const systemCounts = countsByKey(facets?.system, (f) => String(f.id), (f) => f.count);
  const teamCounts = countsByKey(facets?.ownerTeam, (f) => String(f.id), (f) => f.count);
  const named = (id: number, label: string, counts: ReadonlyMap<string, number> | null) => (counts ? `${label} (${counts.get(String(id)) ?? 0})` : label);
  const domains = useQuery({ queryKey: ["domains", "all"], queryFn: listAllDomains });
  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const teams = useQuery({ queryKey: ["teams", "picker", "all"], queryFn: () => listTeams({ page: 1, pageSize: 100, sort: "name" }) });
  const systemOptions = (systems.data?.items ?? [])
    .filter((s) => !slots.domainId || String(s.domainId) === slots.domainId)
    .map((s) => ({ value: String(s.id), label: named(s.id, s.name, systemCounts) }));
  return (
    <>
      <ClearableTextInput label={t("contracts.filter.q")} value={slots.q} onChange={slots.setQ} clearLabel={t("contracts.filter.clearQ")} />
      <Select
        label={t("contracts.field.domain")}
        placeholder={t("common.state.any")}
        data={(domains.data ?? []).map((d) => ({ value: String(d.id), label: named(d.id, d.name, domainCounts) }))}
        value={slots.domainId || null}
        onChange={(v) => {
          slots.setDomainId(v ?? "");
          // A system belongs to one domain — a domain change drops a system that left the set.
          if (v && slots.systemId && !(systems.data?.items ?? []).some((s) => String(s.id) === slots.systemId && String(s.domainId) === v)) slots.setSystemId("");
        }}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearDomain") }}
        searchable
      />
      <Select
        label={t("contracts.field.system")}
        placeholder={t("common.state.any")}
        data={systemOptions}
        value={slots.systemId || null}
        onChange={(v) => slots.setSystemId(v ?? "")}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearSystem") }}
        searchable
      />
      <MultiSelect
        label={t("contracts.field.type")}
        placeholder={slots.types.length === 0 ? t("common.state.any") : undefined}
        data={CONTRACT_TYPES.map((type) => ({ value: type, label: typeCounts ? `${CONTRACT_TYPE_LABEL[type]} (${typeCounts.get(type) ?? 0})` : CONTRACT_TYPE_LABEL[type] }))}
        value={slots.types}
        onChange={slots.setTypes}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearType") }}
        w={220}
      />
      <MultiSelect
        label={t("versions.field.lifecycle")}
        placeholder={slots.lifecycles.length === 0 ? t("common.state.any") : undefined}
        data={LIFECYCLES.map((l) => ({ value: l, label: lifecycleCounts ? `${t(`versions.lifecycle.${l}`)} (${lifecycleCounts.get(l) ?? 0})` : t(`versions.lifecycle.${l}`) }))}
        value={slots.lifecycles}
        onChange={slots.setLifecycles}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLifecycle") }}
        w={240}
      />
      <Select
        label={t("contracts.filter.ownerTeam")}
        placeholder={t("common.state.any")}
        data={(teams.data?.items ?? []).map((team) => ({ value: String(team.id), label: named(team.id, team.name, teamCounts) }))}
        value={slots.ownerTeamId || null}
        onChange={(v) => slots.setOwnerTeamId(v ?? "")}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearOwner") }}
        searchable
      />
      <Switch label={t("contracts.filter.hasErrors")} checked={slots.hasErrors} onChange={(e) => slots.setHasErrors(e.currentTarget.checked)} pb={6} />
    </>
  );
}
