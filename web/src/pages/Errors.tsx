import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Badge, Group, Stack, Table, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconListCheck } from "@tabler/icons-react";
import { getContractErrorFacets, listContractErrors } from "../api/errors";
import CheckSummaryBadges from "../components/CheckSummaryBadges";
import ContractNameLink from "../components/ContractNameLink";
import EmptyState from "../components/EmptyState";
import ErrorFilterControls from "../components/ErrorFilterControls";
import ErrorsSummaryStrip from "../components/ErrorsSummaryStrip";
import FilterPanel from "../components/FilterPanel";
import LifecyclePill from "../components/LifecyclePill";
import OwnerChip from "../components/OwnerChip";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TypeBadge from "../components/TypeBadge";
import { useErrorFilterState } from "../hooks/useErrorFilterState";
import { usePagedSort } from "../hooks/usePagedSort";
import { groupFindingsByCode, groupRowsByContract } from "../utils/errorGroups";
import { versionPath } from "../utils/contractLinks";
import { SEVERITY_COLOR } from "../utils/findings";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "version", "checkedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "errors";

/**
 * The Errors report at `/errors`: every version in the catalog still carrying a finding that
 * matches the shared filters plus the severity/source chips — a read over the stored check
 * snapshots (`contract_versions.findings`), never a new check. Rows are versions grouped
 * visually under their contract; the severity/source chips are SERVER filters (unlike
 * Toadie's client-side error-class pills), so toggling one refetches both the rows and the
 * facet counts.
 */
export default function Errors() {
  const { t, i18n } = useTranslation();
  const filters = useErrorFilterState(SETTINGS_KEY);
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", filters.deps, { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  // Sorting by contract name also keeps each contract's own rows in the server's default
  // (SemVer-descending) order, so the visual grouping below never interleaves — sorting by
  // version or checkedAt alone needs no such pairing.
  const requestSort = sortField === "name" ? `${sortDir === "desc" ? "-name" : "name"},-version` : sortParam;

  // An empty chip set means "show nothing" — the API's empty list means "any", so a
  // match-nothing request can only be represented by never firing it (Toadie's `noKinds` rule).
  const facets = useQuery({
    queryKey: ["contracts", "errors", "facets", filters.values],
    queryFn: () => getContractErrorFacets(filters.values),
    placeholderData: keepPreviousData,
    enabled: !filters.noSelection,
  });
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["contracts", "errors", page, pageSize, sortParam, filters.values],
    queryFn: () => listContractErrors({ page, pageSize, sort: requestSort, ...filters.values }),
    placeholderData: keepPreviousData,
    enabled: !filters.noSelection,
  });

  // groupRowsByContract does the grouping; the page only needs to know which row OPENS a run
  // (renders the contract cell) versus continues one (leaves it blank).
  const rows = groupRowsByContract(data?.items ?? []).flatMap((run) => run.rows.map((row, index) => ({ row, showContract: index === 0 })));

  const columnCount = 4;
  return (
    <Stack gap="md">
      <PageHeader
        title={t("errors.title")}
        description={t("errors.intro")}
        toolbar={
          <ErrorsSummaryStrip
            facets={facets.data}
            severities={filters.slots.severities}
            setSeverities={filters.slots.setSeverities}
            sources={filters.slots.sources}
            setSources={filters.slots.setSources}
          />
        }
      />
      <FilterPanel activeFilterCount={filters.activeCount} storageKey={SETTINGS_KEY}>
        <ErrorFilterControls filters={filters} facets={facets.data ?? null} />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("errors.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("errors.field.contract")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="version" label={t("errors.field.version")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("errors.field.findings")}</Table.Th>
            <SortHeader field="checkedAt" label={t("errors.field.checked")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {filters.noSelection ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconListCheck} label={t("errors.noSelection")} />
              </Table.Td>
            </Table.Tr>
          ) : isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : rows.length > 0 ? (
            rows.map(({ row, showContract }) => (
              <Table.Tr key={row.version.id}>
                <Table.Td>
                  {showContract && (
                    <Stack gap={2}>
                      <Group gap={6} wrap="nowrap">
                        <ContractNameLink id={row.contract.id} name={row.contract.name} />
                        <TypeBadge type={row.contract.type} size="xs" />
                      </Group>
                      <Text size="xs" c="dimmed">
                        {row.contract.system.name} · {row.contract.domain.name}
                      </Text>
                      <OwnerChip owner={row.contract.owner} />
                    </Stack>
                  )}
                </Table.Td>
                <Table.Td>
                  <Group gap={6} wrap="nowrap">
                    <Anchor
                      component={RouterLink}
                      to={versionPath(row.contract.id, row.version.id)}
                      ff="monospace"
                      size="sm"
                      aria-label={t("errors.openVersionAria", { version: row.version.version, contract: row.contract.name })}
                    >
                      {row.version.version}
                    </Anchor>
                    <LifecyclePill lifecycle={row.version.lifecycle} size="xs" />
                    <CheckSummaryBadges errors={row.version.checkErrors} warnings={row.version.checkWarnings} complete={row.version.checkComplete} />
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="wrap">
                    {groupFindingsByCode(row.findings).map((g) => (
                      <Badge key={`${g.severity}-${g.source}-${g.code}`} variant="light" size="xs" color={SEVERITY_COLOR[g.severity]} title={g.message} tt="none" ff="monospace">
                        {g.code}
                        {g.count > 1 ? ` ${t("errors.times", { count: g.count })}` : ""}
                      </Badge>
                    ))}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Tooltip label={formatDateTime(row.version.checkedAt, i18n.language)}>
                    <Text size="sm" c="dimmed">
                      {relativeTimeAgo(row.version.checkedAt, i18n.language)}
                    </Text>
                  </Tooltip>
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconListCheck} label={filters.activeCount > 0 ? t("errors.emptyFiltered") : t("errors.noFindings")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />
    </Stack>
  );
}
