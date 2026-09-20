import { useEffect, useRef, useState } from "react";
import { Alert, Anchor, Badge, Button, Group, Stack, Table, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconCalendarStats, IconEye, IconSettings } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { getLifecycleOverviewSummary, listLifecycleOverview, type LifecycleOverviewRow } from "../api/lifecycleOverview";
import { getReleaseLine, type ReleaseLineResponse, type SupportStatus } from "../api/releaseLines";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import LifecycleOverviewFilters from "../components/LifecycleOverviewFilters";
import LifecycleSummary from "../components/LifecycleSummary";
import OwnerChip from "../components/OwnerChip";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import ReleaseLinePolicyModal from "../components/ReleaseLinePolicyModal";
import RetirementImpactModal from "../components/RetirementImpactModal";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TypeBadge from "../components/TypeBadge";
import { useLifecycleOverviewFilters } from "../hooks/useLifecycleOverviewFilters";
import { usePagedSort } from "../hooks/usePagedSort";
import { contractPath } from "../utils/contractLinks";
import { formatDateTime } from "../utils/relativeTime";
import { supportStatusLabel } from "../utils/releaseLines";
import { loadErrorMessage } from "../utils/saveError";

const SETTINGS_KEY = "lifecycleOverview";
const SORT_FIELDS = ["name", "major", "nextDeadline", "supportStatus"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const STATUS_COLORS: Record<SupportStatus, string> = { UNSPECIFIED: "gray", SUPPORTED: "teal", MAINTENANCE: "orange", END_OF_LIFE: "red" };

export default function LifecycleOverview() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const filters = useLifecycleOverviewFilters(SETTINGS_KEY);
  const paging = usePagedSort<SortField>("nextDeadline", filters.deps, { key: SETTINGS_KEY, sortFields: SORT_FIELDS });
  const [reviewing, setReviewing] = useState<LifecycleOverviewRow | null>(null);
  const [editing, setEditing] = useState<ReleaseLineResponse | null>(null);
  const [editingLineId, setEditingLineId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<unknown>();
  const editRequest = useRef(0);
  const overviewWasRefreshing = useRef(false);
  const summaryFilters = { ...filters.values, attention: undefined };
  const summary = useQuery({ queryKey: ["contracts", "lifecycle-overview", "summary", summaryFilters], queryFn: () => getLifecycleOverviewSummary(summaryFilters), placeholderData: keepPreviousData });
  const overview = useQuery({
    queryKey: ["contracts", "lifecycle-overview", "list", paging.page, paging.pageSize, paging.sortParam, filters.values],
    queryFn: () => listLifecycleOverview({ page: paging.page, pageSize: paging.pageSize, sort: paging.sortParam, ...filters.values }),
    placeholderData: keepPreviousData,
    refetchInterval: ({ state }) => state.data?.items.some((row) => row.usage.cache.refreshing) ? 1500 : false,
  });
  const overviewRefreshing = overview.data?.items.some((row) => row.usage.cache.refreshing) === true;
  useEffect(() => {
    if (overviewWasRefreshing.current && !overviewRefreshing) {
      void queryClient.invalidateQueries({ queryKey: ["contracts", "lifecycle-overview", "summary"] });
    }
    overviewWasRefreshing.current = overviewRefreshing;
  }, [overviewRefreshing, queryClient]);

  async function editPolicy(row: LifecycleOverviewRow) {
    const requestId = ++editRequest.current;
    setEditingLineId(row.id);
    setActionError(undefined);
    try {
      const line = await queryClient.fetchQuery({ queryKey: ["contracts", "release-line", row.contract.id, row.major], queryFn: () => getReleaseLine(row.contract.id, row.major) });
      if (requestId === editRequest.current) setEditing(line);
    } catch (error) {
      if (requestId === editRequest.current) setActionError(error);
    } finally {
      if (requestId === editRequest.current) setEditingLineId(null);
    }
  }

  return <Stack gap="md">
    <PageHeader title={t("lifecycleOverview.title")} description={t("lifecycleOverview.intro")} />
    {summary.isError && <Alert color="red" variant="light" title={t("lifecycleOverview.summaryLoadFailed")}>{loadErrorMessage(summary.error, t)}</Alert>}
    {summary.data && <LifecycleSummary summary={summary.data} selected={filters.values.attention} onSelect={(attention) => filters.slots.setAttention(attention ?? null)} />}
    <FilterPanel activeFilterCount={filters.activeCount} storageKey={SETTINGS_KEY}>
      <LifecycleOverviewFilters filters={filters} summary={summary.data} />
    </FilterPanel>
    {(overview.isError || actionError != null) && <Alert color="red" variant="light" title={t("lifecycleOverview.loadFailed")}>{loadErrorMessage(actionError ?? overview.error, t)}</Alert>}
    <Table.ScrollContainer minWidth={1240}>
      <Table aria-label={t("lifecycleOverview.tableAria")}>
        <Table.Thead><Table.Tr>
          <SortHeader field="name" label={t("lifecycleOverview.column.contract")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <SortHeader field="major" label={t("lifecycleOverview.column.line")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th style={{ minWidth: 165 }}>{t("lifecycleOverview.column.owner")}</Table.Th>
          <SortHeader field="supportStatus" label={t("lifecycleOverview.column.support")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <SortHeader field="nextDeadline" label={t("lifecycleOverview.column.deadline")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th style={{ minWidth: 170 }}>{t("lifecycleOverview.column.plan")}</Table.Th>
          <Table.Th style={{ minWidth: 150 }}>{t("lifecycleOverview.column.consumers")}</Table.Th>
          <Table.Th style={{ minWidth: 90 }} aria-label={t("common.table.operations")} />
        </Table.Tr></Table.Thead>
        <Table.Tbody>
          {overview.isLoading && !overview.data ? <TableLoadingRow colSpan={8} /> : overview.data?.items.length ? overview.data.items.map((row) =>
            <OverviewRow key={row.id} row={row} locale={i18n.language} editPending={editingLineId === row.id} onReview={() => setReviewing(row)} onEdit={() => void editPolicy(row)} />
          ) : !overview.isError ? <Table.Tr><Table.Td colSpan={8}><EmptyState icon={IconCalendarStats} label={filters.activeCount ? t("lifecycleOverview.emptyFiltered") : t("lifecycleOverview.empty")} /></Table.Td></Table.Tr> : null}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
    <PaginationBar total={overview.data?.total ?? 0} page={paging.page} pageSize={paging.pageSize} onPageChange={paging.setPage} onPageSizeChange={paging.setPageSize} />
    {reviewing && <RetirementImpactModal contractId={reviewing.contract.id} major={reviewing.major} canWrite={reviewing.contract.canWrite} onClose={() => setReviewing(null)} />}
    {editing && <ReleaseLinePolicyModal key={`${editing.contractId}:${editing.major}`} contractId={editing.contractId} line={editing} onClose={() => setEditing(null)} />}
  </Stack>;
}

function OverviewRow({ row, locale, editPending, onReview, onEdit }: { row: LifecycleOverviewRow; locale: string; editPending: boolean; onReview: () => void; onEdit: () => void }) {
  const { t } = useTranslation();
  const line = `${row.major}.x`;
  return <Table.Tr>
    <Table.Td style={{ minWidth: 230 }}><Stack gap={2}>
      <Group gap="xs"><Anchor component={RouterLink} to={contractPath(row.contract.id)}>{row.contract.name}</Anchor><TypeBadge type={row.contract.type} /></Group>
      <Text size="xs" c="dimmed">{row.contract.domain.name} · {row.contract.system.name}</Text>
    </Stack></Table.Td>
    <Table.Td style={{ minWidth: 75 }}><Text ff="monospace" fw={600}>{line}</Text></Table.Td>
    <Table.Td><OwnerChip owner={row.contract.owner} /></Table.Td>
    <Table.Td style={{ minWidth: 150 }}><Badge color={STATUS_COLORS[row.supportStatus]} variant="light">{supportStatusLabel(row.supportStatus, t)}</Badge></Table.Td>
    <Table.Td style={{ minWidth: 210 }}><DeadlineCell row={row} locale={locale} /></Table.Td>
    <Table.Td><PlanCell row={row} /></Table.Td>
    <Table.Td><UsageCell row={row} locale={locale} /></Table.Td>
    <Table.Td><Group gap="xs" wrap="nowrap">
      <Tooltip label={t("lifecycleOverview.reviewImpact", { contract: row.contract.name, line })}><Button variant="default" size="xs" px={8} aria-label={t("lifecycleOverview.reviewImpact", { contract: row.contract.name, line })} onClick={onReview}><IconEye size={16} /></Button></Tooltip>
      {row.contract.canWrite && <Tooltip label={t("lifecycleOverview.editPolicy", { contract: row.contract.name, line })}><Button variant="default" size="xs" px={8} aria-label={t("lifecycleOverview.editPolicy", { contract: row.contract.name, line })} onClick={onEdit} loading={editPending}><IconSettings size={16} /></Button></Tooltip>}
    </Group></Table.Td>
  </Table.Tr>;
}

function formatDate(value: string, locale: string) {
  return new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeZone: "UTC" }).format(new Date(`${value}T00:00:00Z`));
}

function DeadlineCell({ row, locale }: { row: LifecycleOverviewRow; locale: string }) {
  const { t } = useTranslation();
  if (!row.deprecatesOn && !row.supportEndsOn) return <Text size="sm" c="dimmed">{t("lifecycleOverview.deadline.noDates")}</Text>;
  return <Stack gap={2}>
    {row.deprecatesOn && <Text size="sm">{t("lifecycleOverview.deadline.deprecation", { date: formatDate(row.deprecatesOn, locale) })}</Text>}
    {row.supportEndsOn && <Text size="sm">{t("lifecycleOverview.deadline.supportEnd", { date: formatDate(row.supportEndsOn, locale) })}</Text>}
    {row.supportEnded && <Badge color="red" variant="light" size="xs">{t("lifecycleOverview.deadline.supportReached")}</Badge>}
    {!row.supportEnded && row.deadlineSoon && <Badge color="orange" variant="light" size="xs">{t("lifecycleOverview.summary.deadlines30")}</Badge>}
  </Stack>;
}

function PlanCell({ row }: { row: LifecycleOverviewRow }) {
  const { t } = useTranslation();
  const hasPlan = row.hasMigrationGuide || row.replacement != null;
  const planComplete = row.hasMigrationGuide && row.replacement?.available === true;
  const planKey = row.supportStatus === "END_OF_LIFE" && hasPlan
    ? "recorded"
    : row.migrationIncomplete
      ? "incomplete"
      : planComplete
        ? "complete"
        : "none";
  return <Stack gap={2}>
    <Badge color={planKey === "incomplete" ? "orange" : planKey === "complete" ? "teal" : "gray"} variant="light" size="xs">
      {t(`lifecycleOverview.plan.${planKey}`)}
    </Badge>
    {row.replacement && <Text size="xs" c={row.replacement.available ? "dimmed" : "orange"}>
      {row.replacement.available
        ? `${row.replacement.contractName}${row.replacement.major == null ? "" : ` · ${row.replacement.major}.x`}`
        : t("contracts.releaseLines.replacementUnavailable", { id: row.replacement.contractId, line: row.replacement.major == null ? "" : ` · ${row.replacement.major}.x` })}
    </Text>}
  </Stack>;
}

function UsageCell({ row, locale }: { row: LifecycleOverviewRow; locale: string }) {
  const { t } = useTranslation();
  const cacheKey = row.usage.cache.state === "NEVER_SYNCED" ? "neverSynced" : row.usage.cache.state.toLowerCase() as "current" | "stale" | "disabled" | "disconnected" | "unlinked";
  const cacheColor = row.usage.cache.state === "CURRENT" ? "teal" : row.usage.cache.state === "STALE" ? "orange" : "gray";
  return <Stack gap={2}>
    <Text size="sm" fw={500}>{row.usage.consumerCount == null ? t("lifecycleOverview.usage.unavailable") : t("lifecycleOverview.usage.count", { count: row.usage.consumerCount })}</Text>
    <Tooltip label={t("lifecycleOverview.usage.scope")}><Badge size="xs" variant="light" color={cacheColor}>{t(`toadie.status.${cacheKey}`)}</Badge></Tooltip>
    {row.usage.cache.lastSuccessAt != null && <Text size="xs" c="dimmed">{t("lifecycleOverview.usage.freshness", { date: formatDateTime(row.usage.cache.lastSuccessAt, locale) })}</Text>}
  </Stack>;
}
