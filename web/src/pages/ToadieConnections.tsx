import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Button, Menu, Stack, Table, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconBrandDatabricks, IconDatabaseImport, IconPencil, IconPlus, IconRefresh, IconTrash } from "@tabler/icons-react";
import { deleteToadieConnection, listToadieConnections, refreshToadieConnection, type ToadieConnection } from "../api/toadie";
import { isAdmin } from "../api/session";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import ToadieConnectionEditorModal from "../components/ToadieConnectionEditorModal";
import ToadieRegistrySyncModal from "../components/ToadieRegistrySyncModal";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["name", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "toadieConnections";

function connectionStatus(row: ToadieConnection) {
  if (!row.enabled) return { key: "disabled" as const, color: "gray" };
  if (row.refreshing) return { key: "refreshing" as const, color: "blue" };
  if (row.lastSuccessAt == null) return { key: "neverSynced" as const, color: "gray" };
  if (row.stale || row.lastErrorCode) return { key: "stale" as const, color: "orange" };
  return { key: "current" as const, color: "teal" };
}

export default function ToadieConnections() {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const admin = isAdmin();
  const paging = usePagedSort<SortField>("name", [], { key: SETTINGS_KEY, sortFields: SORT_FIELDS });
  const query = useQuery({
    queryKey: ["toadie", "connections", paging.page, paging.pageSize, paging.sortParam],
    queryFn: () => listToadieConnections({ page: paging.page, pageSize: paging.pageSize, sort: paging.sortParam }),
    placeholderData: keepPreviousData,
    refetchInterval: ({ state }) => state.data?.items.some((row) => row.refreshing) ? 1500 : false,
  });
  const [editor, setEditor] = useState<ToadieConnection | "new" | null>(null);
  const [expandRegistryMapping, setExpandRegistryMapping] = useState(false);
  const [registrySync, setRegistrySync] = useState<ToadieConnection | null>(null);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const remove = useDeleteConfirm<ToadieConnection>({
    mutationFn: (row) => deleteToadieConnection(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["toadie"] }),
    successMessage: t("toadie.toast.deleted"),
  });

  async function refresh(row: ToadieConnection) {
    setRefreshError(null);
    try {
      await refreshToadieConnection(row.id);
      showSuccessToast(t("toadie.refreshAccepted"));
      await queryClient.invalidateQueries({ queryKey: ["toadie"] });
    } catch (error) {
      setRefreshError(saveErrorMessage(error, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" }));
    }
  }

  const columnCount = admin ? 6 : 5;
  return (
    <Stack gap="md">
      <PageHeader title={t("toadie.title")} description={t("toadie.intro")} actions={admin && <Button leftSection={<IconPlus size={16} />} onClick={() => { setExpandRegistryMapping(false); setEditor("new"); }}>{t("toadie.newConnection")}</Button>} />
      {(query.isError || refreshError) && <Alert color="red" variant="light" title={query.isError ? t("toadie.loadFailed") : t("toadie.refreshFailed")}>{query.isError ? loadErrorMessage(query.error, t) : refreshError}</Alert>}
      <Table>
        <Table.Thead><Table.Tr>
          <SortHeader field="name" label={t("common.field.name")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th>{t("toadie.field.browserUrl")}</Table.Th>
          <Table.Th>{t("toadie.column.status")}</Table.Th>
          <Table.Th>{t("toadie.column.lastSuccess")}</Table.Th>
          <Table.Th>{t("toadie.column.interval")}</Table.Th>
          {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
        </Table.Tr></Table.Thead>
        <Table.Tbody>
          {query.isLoading && !query.data ? <TableLoadingRow colSpan={columnCount} /> : query.data?.items.length ? query.data.items.map((row) => {
            const status = connectionStatus(row);
            return <Table.Tr key={row.id}>
              <Table.Td><Text size="sm" fw={500}>{row.name}</Text></Table.Td>
              <Table.Td><Text component="a" href={row.browserUrl} target="_blank" rel="noreferrer" size="sm">{row.browserUrl}</Text></Table.Td>
              <Table.Td><Badge color={status.color} variant="light">{t(`toadie.status.${status.key}`)}</Badge></Table.Td>
              <Table.Td>{row.lastSuccessAt == null ? <Text size="sm" c="dimmed">—</Text> : <Tooltip label={formatDateTime(row.lastSuccessAt, i18n.language)}><Text size="sm">{relativeTimeAgo(row.lastSuccessAt, i18n.language)}</Text></Tooltip>}</Table.Td>
              <Table.Td><Text size="sm">{row.refreshIntervalMinutes} min</Text></Table.Td>
              {admin && <Table.Td ta="right"><RowActionsMenu label={t("common.table.operationsAria", { name: row.name })}>
                <Menu.Item leftSection={<IconRefresh size={14} />} disabled={row.refreshing || !row.enabled} onClick={() => void refresh(row)}>{t("toadie.refresh")}</Menu.Item>
                <Menu.Item leftSection={<IconDatabaseImport size={14} />} onClick={() => setRegistrySync(row)}>{t("toadie.registry.action")}</Menu.Item>
                <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => { setExpandRegistryMapping(false); setEditor(row); }}>{t("common.action.edit")}</Menu.Item>
                <Menu.Divider />
                <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(row)}>{t("common.action.delete")}</Menu.Item>
              </RowActionsMenu></Table.Td>}
            </Table.Tr>;
          }) : !query.isError ? <Table.Tr><Table.Td colSpan={columnCount}><EmptyState icon={IconBrandDatabricks} label={t("toadie.empty")} /></Table.Td></Table.Tr> : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={query.data?.total ?? 0} page={paging.page} pageSize={paging.pageSize} onPageChange={paging.setPage} onPageSizeChange={paging.setPageSize} />
      {editor != null && <ToadieConnectionEditorModal target={editor === "new" ? null : editor} expandRegistryMapping={expandRegistryMapping} onClose={() => setEditor(null)} onSaved={async () => { setEditor(null); await queryClient.invalidateQueries({ queryKey: ["toadie"] }); }} />}
      {registrySync != null && <ToadieRegistrySyncModal connection={registrySync} onClose={() => setRegistrySync(null)} onConfigure={() => {
        setRegistrySync(null);
        setExpandRegistryMapping(true);
        setEditor(registrySync);
      }} onApplied={async () => {
        setRegistrySync(null);
        await Promise.all([
          queryClient.invalidateQueries({ queryKey: ["toadie"] }),
          queryClient.invalidateQueries({ queryKey: ["domains"] }),
          queryClient.invalidateQueries({ queryKey: ["systems"] }),
          queryClient.invalidateQueries({ queryKey: ["teams"] }),
          queryClient.invalidateQueries({ queryKey: ["contracts"] }),
        ]);
      }} />}
      <ConfirmDeleteModal confirm={remove} title={t("toadie.deleteTitle")} errorTitle={t("toadie.deleteFailed")} errorMessage={(error) => saveErrorMessage(error, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })} body={(row) => t("toadie.deleteBody", { name: row.name })} />
    </Stack>
  );
}
