import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Button, Group, Menu, Stack, Table, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconFileImport, IconFileText, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { deleteContract, getContractFacets, listContracts, type ContractResponse } from "../api/contracts";
import CheckSummaryBadges from "../components/CheckSummaryBadges";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import ContractFilterControls from "../components/ContractFilterControls";
import ContractNameLink from "../components/ContractNameLink";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import LifecyclePill from "../components/LifecyclePill";
import OwnerChip from "../components/OwnerChip";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TypeBadge from "../components/TypeBadge";
import { useContractFilterState } from "../hooks/useContractFilterState";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { editContractPath, importContractPath, newContractPath } from "../utils/contractLinks";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "type", "createdAt", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "contracts";

/**
 * The contracts list (`/contracts`): every contract the catalog holds, with the shared filter
 * set (text, domain, system, type, lifecycle of the latest version, owning team, only-flawed).
 * Everyone reads; a row's Edit/Delete appear only where the server says `canWrite`.
 */
export default function Contracts() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const filters = useContractFilterState(SETTINGS_KEY);
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", filters.deps, { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  // The facet counts ride the same filters; a stale count while the next one loads beats a flicker.
  const facets = useQuery({
    queryKey: ["contracts", "facets", filters.values],
    queryFn: () => getContractFacets(filters.values),
    placeholderData: keepPreviousData,
  });
  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["contracts", "list", page, pageSize, sortParam, filters.values],
    queryFn: () => listContracts({ page, pageSize, sort: sortParam, ...filters.values }),
    placeholderData: keepPreviousData,
  });

  const remove = useDeleteConfirm<ContractResponse>({
    mutationFn: (row) => deleteContract(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["contracts"] }),
    successMessage: t("contracts.toast.deleted"),
  });

  const columnCount = 7;
  return (
    <Stack gap="md">
      <PageHeader
        title={t("contracts.title")}
        description={t("contracts.intro")}
        actions={
          <>
            <Button component={RouterLink} to={importContractPath} variant="default" leftSection={<IconFileImport size={16} />}>
              {t("contracts.import")}
            </Button>
            <Button component={RouterLink} to={newContractPath} leftSection={<IconPlus size={16} />}>
              {t("contracts.newContract")}
            </Button>
          </>
        }
      />
      <FilterPanel activeFilterCount={filters.activeCount} storageKey={SETTINGS_KEY}>
        <ContractFilterControls filters={filters} facets={facets.data ?? null} />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("contracts.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="type" label={t("contracts.field.type")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("contracts.field.system")}</Table.Th>
            <Table.Th>{t("contracts.field.owner")}</Table.Th>
            <Table.Th>{t("contracts.column.latest")}</Table.Th>
            <SortHeader field="updatedAt" label={t("contracts.column.updated")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((contract) => (
              <Table.Tr key={contract.id}>
                <Table.Td>
                  <Stack gap={0}>
                    <ContractNameLink id={contract.id} name={contract.name} />
                    {contract.description && (
                      <Text size="xs" c="dimmed" lineClamp={1}>
                        {contract.description}
                      </Text>
                    )}
                  </Stack>
                </Table.Td>
                <Table.Td>
                  <TypeBadge type={contract.type} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{contract.system.name}</Text>
                  <Text size="xs" c="dimmed">
                    {contract.domain.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <OwnerChip owner={contract.owner} />
                </Table.Td>
                <Table.Td>
                  {contract.latestVersion ? (
                    <Group gap={6} wrap="nowrap">
                      <Text size="sm" ff="monospace">
                        {contract.latestVersion.version}
                      </Text>
                      <LifecyclePill lifecycle={contract.latestVersion.lifecycle} size="xs" />
                      <CheckSummaryBadges errors={contract.latestVersion.checkErrors} warnings={contract.latestVersion.checkWarnings} complete={contract.latestVersion.checkComplete} />
                    </Group>
                  ) : (
                    <Text size="sm" c="dimmed">
                      {t("contracts.noVersions")}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Tooltip label={formatDateTime(contract.updatedAt, i18n.language)}>
                    <Text size="sm" c="dimmed">
                      {relativeTimeAgo(contract.updatedAt, i18n.language)}
                    </Text>
                  </Tooltip>
                </Table.Td>
                <Table.Td style={{ width: 1 }} ta="right">
                  {contract.canWrite && (
                    <RowActionsMenu label={t("common.table.operationsAria", { name: contract.name })}>
                      <Menu.Item component={RouterLink} to={editContractPath(contract.id)} leftSection={<IconPencil size={14} />} aria-label={t("common.action.editAria", { name: contract.name })}>
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(contract)} aria-label={t("common.action.deleteAria", { name: contract.name })}>
                        {t("common.action.delete")}
                      </Menu.Item>
                    </RowActionsMenu>
                  )}
                </Table.Td>
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconFileText} label={filters.activeCount > 0 ? t("contracts.emptyFiltered") : t("contracts.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />
      <ConfirmDeleteModal
        confirm={remove}
        title={t("contracts.deleteTitle")}
        errorTitle={t("contracts.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "contracts.deleteHasPublished", forbidden: "contracts.saveForbidden", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(contract) => t("contracts.deleteBody", { name: contract.name, count: contract.versionCount })}
      />
    </Stack>
  );
}
