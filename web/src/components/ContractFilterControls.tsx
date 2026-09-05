import { useTranslation } from "react-i18next";
import { MultiSelect, Select, Switch } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { listAllDomains } from "../api/domains";
import { listSystems } from "../api/systems";
import { listTeams } from "../api/teams";
import type { ContractFilterState } from "../hooks/useContractFilterState";
import { CONTRACT_TYPE_LABEL, CONTRACT_TYPES } from "../utils/contractForm";
import { LIFECYCLES } from "../utils/lifecycle";
import ClearableTextInput from "./ClearableTextInput";

/** The controls for useContractFilterState, rendered inside a FilterPanel by the list and the tree. */
export default function ContractFilterControls({ filters }: { filters: ContractFilterState }) {
  const { t } = useTranslation();
  const { slots } = filters;
  const domains = useQuery({ queryKey: ["domains", "all"], queryFn: listAllDomains });
  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const teams = useQuery({ queryKey: ["teams", "picker", "all"], queryFn: () => listTeams({ page: 1, pageSize: 100, sort: "name" }) });
  const systemOptions = (systems.data?.items ?? [])
    .filter((s) => !slots.domainId || String(s.domainId) === slots.domainId)
    .map((s) => ({ value: String(s.id), label: s.name }));
  return (
    <>
      <ClearableTextInput label={t("contracts.filter.q")} value={slots.q} onChange={slots.setQ} clearLabel={t("contracts.filter.clearQ")} />
      <Select
        label={t("contracts.field.domain")}
        placeholder={t("common.state.any")}
        data={(domains.data ?? []).map((d) => ({ value: String(d.id), label: d.name }))}
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
        data={CONTRACT_TYPES.map((type) => ({ value: type, label: CONTRACT_TYPE_LABEL[type] }))}
        value={slots.types}
        onChange={slots.setTypes}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearType") }}
        w={220}
      />
      <MultiSelect
        label={t("versions.field.lifecycle")}
        placeholder={slots.lifecycles.length === 0 ? t("common.state.any") : undefined}
        data={LIFECYCLES.map((l) => ({ value: l, label: t(`versions.lifecycle.${l}`) }))}
        value={slots.lifecycles}
        onChange={slots.setLifecycles}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLifecycle") }}
        w={240}
      />
      <Select
        label={t("contracts.filter.ownerTeam")}
        placeholder={t("common.state.any")}
        data={(teams.data?.items ?? []).map((team) => ({ value: String(team.id), label: team.name }))}
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
