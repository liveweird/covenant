import { MultiSelect, Select } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import type { LifecycleOverviewSummary } from "../api/lifecycleOverview";
import { listAllDomains } from "../api/domains";
import { listAllSystems } from "../api/systems";
import { listAllTeams } from "../api/teams";
import type { LifecycleOverviewFilterState } from "../hooks/useLifecycleOverviewFilters";
import { CONTRACT_TYPE_LABEL, CONTRACT_TYPES } from "../utils/contractForm";
import { SUPPORT_STATUSES, supportStatusLabel } from "../utils/releaseLines";
import ClearableTextInput from "./ClearableTextInput";

export default function LifecycleOverviewFilters({ filters, summary }: { filters: LifecycleOverviewFilterState; summary?: LifecycleOverviewSummary }) {
  const { t } = useTranslation();
  const { slots } = filters;
  const domains = useQuery({ queryKey: ["domains", "all"], queryFn: listAllDomains });
  const selectedDomainId = slots.domainId ? Number(slots.domainId) : undefined;
  const systems = useQuery({ queryKey: ["systems", "all", selectedDomainId], queryFn: () => listAllSystems(selectedDomainId) });
  // The contracts filter owns ["teams","picker","all"] with a paged response shape.
  // Keep the all-pages array on a distinct key so navigation cannot reuse the wrong shape.
  const teams = useQuery({ queryKey: ["teams", "picker", "all-pages"], queryFn: () => listAllTeams() });
  const systemOptions = (systems.data ?? [])
    .map((system) => ({ value: String(system.id), label: system.name }));
  const ownerOptions = [
    { group: t("contracts.owner.teams"), items: (teams.data ?? []).map((team) => ({ value: `TEAM:${team.id}`, label: team.name })) },
    { group: t("contracts.owner.users"), items: (summary?.ownerUser ?? []).map((user) => ({ value: `USER:${user.id}`, label: `${user.name} (${user.count})` })) },
  ];

  return <>
    <ClearableTextInput label={t("contracts.filter.q")} value={slots.q} onChange={slots.setQ} clearLabel={t("contracts.filter.clearQ")} />
    <Select label={t("contracts.field.domain")} placeholder={t("common.state.any")} searchable clearable
      clearButtonProps={{ "aria-label": t("common.filter.clearDomain") }} data={(domains.data ?? []).map((domain) => ({ value: String(domain.id), label: domain.name }))}
      value={slots.domainId || null} onChange={(value) => {
        slots.setDomainId(value ?? "");
        if (value && slots.systemId && !(systems.data ?? []).some((system) => String(system.id) === slots.systemId && String(system.domainId) === value)) slots.setSystemId("");
      }} />
    <Select label={t("contracts.field.system")} placeholder={t("common.state.any")} searchable clearable
      clearButtonProps={{ "aria-label": t("common.filter.clearSystem") }} data={systemOptions}
      value={slots.systemId || null} onChange={(value) => slots.setSystemId(value ?? "")} />
    <MultiSelect label={t("contracts.field.type")} placeholder={slots.types.length ? undefined : t("common.state.any")} clearable
      clearButtonProps={{ "aria-label": t("common.filter.clearType") }} data={CONTRACT_TYPES.map((type) => ({ value: type, label: CONTRACT_TYPE_LABEL[type] }))}
      value={slots.types} onChange={slots.setTypes} />
    <Select label={t("lifecycleOverview.filter.owner")} placeholder={t("common.state.any")} searchable clearable
      clearButtonProps={{ "aria-label": t("lifecycleOverview.filter.clearOwner") }} data={ownerOptions}
      value={slots.owner || null} onChange={(value) => slots.setOwner(value ?? "")} />
    <MultiSelect label={t("lifecycleOverview.filter.support")} placeholder={slots.supportStatuses.length ? undefined : t("common.state.any")} clearable
      clearButtonProps={{ "aria-label": t("lifecycleOverview.filter.clearSupport") }} data={SUPPORT_STATUSES.map((status) => ({ value: status, label: supportStatusLabel(status, t) }))}
      value={slots.supportStatuses} onChange={slots.setSupportStatuses} />
    <Select label={t("lifecycleOverview.filter.deadline")} placeholder={t("common.state.any")} clearable
      clearButtonProps={{ "aria-label": t("lifecycleOverview.filter.clearDeadline") }} value={slots.deadline}
      onChange={(value) => slots.setDeadline(value as typeof slots.deadline)} data={[
        { value: "REACHED", label: t("lifecycleOverview.filter.deadlineReached") },
        { value: "NEXT_30_DAYS", label: t("lifecycleOverview.filter.deadlineNext30") },
        { value: "NONE", label: t("lifecycleOverview.filter.noDates") },
      ]} />
    <Select label={t("lifecycleOverview.filter.attention")} placeholder={t("common.state.any")} clearable
      clearButtonProps={{ "aria-label": t("lifecycleOverview.filter.clearAttention") }} value={slots.attention}
      onChange={(value) => slots.setAttention(value as typeof slots.attention)} data={[
        { value: "DEADLINE_SOON", label: t("lifecycleOverview.filter.attentionDeadlineSoon") },
        { value: "SUPPORT_ENDED", label: t("lifecycleOverview.filter.attentionSupportEnded") },
        { value: "MIGRATION_INCOMPLETE", label: t("lifecycleOverview.filter.attentionMigrationIncomplete") },
        { value: "USAGE_UNCERTAIN", label: t("lifecycleOverview.filter.attentionUsageUncertain") },
      ]} />
  </>;
}
