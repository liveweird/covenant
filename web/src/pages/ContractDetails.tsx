import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Menu, Paper, Stack, Table, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconArrowsDiff, IconDots, IconDownload, IconFileText, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { deleteContract, exportContract, getContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { deleteVersion, listVersions, type VersionListItem } from "../api/versions";
import CheckSummaryBadges from "../components/CheckSummaryBadges";
import ContractHistory from "../components/ContractHistory";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EditPageLoadState from "../components/EditPageLoadState";
import EmptyState from "../components/EmptyState";
import FollowButton from "../components/FollowButton";
import LifecyclePill from "../components/LifecyclePill";
import OwnerChip from "../components/OwnerChip";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TypeBadge from "../components/TypeBadge";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { useVersionDownload } from "../hooks/useVersionDownload";
import { contractsPath, editContractPath, newVersionPath, versionDiffPath, versionPath } from "../utils/contractLinks";
import { downloadText } from "../utils/document";
import { isDeletable } from "../utils/lifecycle";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["version", "lifecycle", "createdAt", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

/**
 * One contract (`/contracts/:id`): its placement and owner, then the versions table (highest
 * first). Write actions — New version, Edit, Delete, a row's Delete — appear only with
 * `canWrite`; Download, Compare and Export are for everyone.
 */
export default function ContractDetails() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } = usePagedSort<SortField>("version", [id], {
    key: "versions",
    sortFields: SORT_FIELDS,
  });
  // The versions table opens highest-first; usePagedSort starts ascending, so the default is flipped here.
  const effectiveSort = sortField === "version" && sortDir === "asc" && sortParam === "version" ? "-version" : sortParam;
  const versions = useQuery({
    queryKey: ["contracts", "versions", id, page, pageSize, effectiveSort],
    queryFn: () => listVersions(id, { page, pageSize, sort: effectiveSort }),
    enabled: Number.isFinite(id),
    placeholderData: keepPreviousData,
  });
  const downloads = useVersionDownload();

  const removeVersion = useDeleteConfirm<VersionListItem>({
    mutationFn: (row) => deleteVersion(id, row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["contracts"] }),
    successMessage: t("versions.toast.deleted"),
  });
  const removeContract = useDeleteConfirm<{ id: number; name: string; versionCount: number }>({
    mutationFn: (row) => deleteContract(row.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      navigate(contractsPath, { replace: true });
    },
    successMessage: t("contracts.toast.deleted"),
  });

  if (contract.isLoading || contract.isError || !contract.data) {
    const notFound = contract.error instanceof ApiError && contract.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={contract.isLoading}
        message={notFound ? t("contracts.notFound") : loadErrorMessage(contract.error, t)}
        backTo={contractsPath}
        backLabel={t("contracts.backToList")}
      />
    );
  }
  const data = contract.data;
  const latest = data.latestVersion ?? null;

  async function handleExport() {
    try {
      const bundle = await exportContract(id);
      downloadText(`${data.system.name}__${data.name}__export.json`, JSON.stringify(bundle, null, 2), "application/json");
      showSuccessToast(t("contracts.toast.exported"));
    } catch {
      // The export is a GET of what the page already shows — a failure surfaces as the download simply not starting.
    }
  }

  const columnCount = 7;
  return (
    <Stack gap="md">
      <PageHeader
        title={data.name}
        description={data.description ?? undefined}
        backTo={{ to: contractsPath, label: t("contracts.backToList") }}
        actions={
          <>
            <FollowButton contract={data} />
            {data.versionCount >= 2 && (
              <Button component={RouterLink} to={versionDiffPath(id)} variant="default" leftSection={<IconArrowsDiff size={16} />}>
                {t("versions.compare")}
              </Button>
            )}
            {data.canWrite && (
              <Button component={RouterLink} to={newVersionPath(id, latest?.id)} leftSection={<IconPlus size={16} />}>
                {t("versions.newVersion")}
              </Button>
            )}
            <Menu>
              <Menu.Target>
                <Button variant="default" leftSection={<IconDots size={16} />} aria-label={t("contracts.moreActions")}>
                  {t("contracts.more")}
                </Button>
              </Menu.Target>
              <Menu.Dropdown>
                {data.canWrite && (
                  <Menu.Item component={RouterLink} to={editContractPath(id)} leftSection={<IconPencil size={14} />}>
                    {t("common.action.edit")}
                  </Menu.Item>
                )}
                <Menu.Item leftSection={<IconDownload size={14} />} onClick={() => void handleExport()} disabled={data.versionCount === 0}>
                  {t("contracts.export")}
                </Menu.Item>
                {data.canWrite && (
                  <>
                    <Menu.Divider />
                    <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => removeContract.requestDelete({ id, name: data.name, versionCount: data.versionCount })}>
                      {t("common.action.delete")}
                    </Menu.Item>
                  </>
                )}
              </Menu.Dropdown>
            </Menu>
          </>
        }
      />
      <Paper withBorder p="md" radius="md">
        <Group gap="xl" wrap="wrap">
          <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {t("contracts.field.type")}
            </Text>
            <TypeBadge type={data.type} />
          </Stack>
          <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {t("contracts.field.system")}
            </Text>
            <Text size="sm">
              {data.domain.name} / {data.system.name}
            </Text>
          </Stack>
          <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {t("contracts.field.owner")}
            </Text>
            <OwnerChip owner={data.owner} />
          </Stack>
          <Stack gap={2}>
            <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
              {t("contracts.column.latest")}
            </Text>
            {latest ? (
              <Group gap={6}>
                <Text size="sm" ff="monospace">
                  {latest.version}
                </Text>
                <LifecyclePill lifecycle={latest.lifecycle} size="xs" />
              </Group>
            ) : (
              <Text size="sm" c="dimmed">
                {t("contracts.noVersions")}
              </Text>
            )}
          </Stack>
        </Group>
      </Paper>
      {downloads.error != null && (
        <Alert color="red" variant="light" withCloseButton onClose={downloads.dismissError} title={t("versions.downloadFailed")}>
          {loadErrorMessage(downloads.error, t)}
        </Alert>
      )}
      {versions.isError && (
        <Alert color="red" variant="light" title={t("versions.loadFailed")}>
          {loadErrorMessage(versions.error, t)}
        </Alert>
      )}
      <Table aria-label={t("versions.tableAria", { name: data.name })}>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="version" label={t("versions.field.version")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="lifecycle" label={t("versions.field.lifecycle")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("versions.field.title")}</Table.Th>
            <Table.Th>{t("versions.field.specVersion")}</Table.Th>
            <Table.Th>{t("findings.title")}</Table.Th>
            <SortHeader field="updatedAt" label={t("contracts.column.updated")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {versions.isLoading && !versions.data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : versions.data && versions.data.items.length > 0 ? (
            versions.data.items.map((v) => (
              <Table.Tr key={v.id}>
                <Table.Td>
                  <Button component={RouterLink} to={versionPath(id, v.id)} variant="subtle" size="compact-sm" ff="monospace" px={4} aria-label={t("versions.openAria", { version: v.version })}>
                    {v.version}
                  </Button>
                </Table.Td>
                <Table.Td>
                  <LifecyclePill lifecycle={v.lifecycle} />
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={v.docTitle ? undefined : "dimmed"} lineClamp={1}>
                    {v.docTitle ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" ff="monospace">
                    {v.specVersion ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <CheckSummaryBadges errors={v.checkErrors} warnings={v.checkWarnings} complete={v.checkComplete} />
                </Table.Td>
                <Table.Td>
                  <Tooltip label={formatDateTime(v.updatedAt, i18n.language)}>
                    <Text size="sm" c="dimmed">
                      {relativeTimeAgo(v.updatedAt, i18n.language)}
                    </Text>
                  </Tooltip>
                </Table.Td>
                <Table.Td style={{ width: 1 }} ta="right">
                  <RowActionsMenu label={t("common.table.operationsAria", { name: v.version })} loading={downloads.downloadingId === v.id}>
                    <Menu.Item
                      leftSection={<IconDownload size={14} />}
                      onClick={() => void downloads.download({ contractId: id, versionId: v.id, system: data.system.name, contract: data.name, version: v.version, format: v.format })}
                    >
                      {t("versions.download")}
                    </Menu.Item>
                    {latest && latest.id !== v.id && (
                      <Menu.Item component={RouterLink} to={versionDiffPath(id, v.id, latest.id)} leftSection={<IconArrowsDiff size={14} />}>
                        {t("versions.compareWithLatest")}
                      </Menu.Item>
                    )}
                    {data.canWrite && (
                      <Menu.Item component={RouterLink} to={newVersionPath(id, v.id)} leftSection={<IconPlus size={14} />}>
                        {t("versions.newFromThis")}
                      </Menu.Item>
                    )}
                    {data.canWrite && isDeletable(v.lifecycle) && (
                      <>
                        <Menu.Divider />
                        <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => removeVersion.requestDelete(v)} aria-label={t("versions.deleteAria", { version: v.version })}>
                          {t("common.action.delete")}
                        </Menu.Item>
                      </>
                    )}
                  </RowActionsMenu>
                </Table.Td>
              </Table.Tr>
            ))
          ) : !versions.isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconFileText} label={data.canWrite ? t("versions.emptyWriter") : t("versions.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={versions.data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />
      <ContractHistory contractId={id} />
      <ConfirmDeleteModal
        confirm={removeVersion}
        title={t("versions.deleteTitle")}
        errorTitle={t("versions.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "versions.deleteNotDraft", forbidden: "contracts.saveForbidden", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(v) => t("versions.deleteBody", { version: v.version, name: data.name })}
      />
      <ConfirmDeleteModal
        confirm={removeContract}
        title={t("contracts.deleteTitle")}
        errorTitle={t("contracts.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "contracts.deleteHasPublished", forbidden: "contracts.saveForbidden", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(c) => t("contracts.deleteBody", { name: c.name, count: c.versionCount })}
      />
    </Stack>
  );
}
