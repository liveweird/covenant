import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Button, Group, Menu, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconPlugConnected, IconPlus, IconTrash } from "@tabler/icons-react";
import { deleteEnvironment, listEnvironments, type EnvironmentResponse } from "../api/environments";
import { isAdmin } from "../api/session";
import { listSystems } from "../api/systems";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import EnvironmentEditorModal from "../components/EnvironmentEditorModal";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

const SORT_FIELDS = ["name", "systemId", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "environments";

/**
 * The environments registry (`/environments`) — where the server connects when someone tries a
 * contract: an HTTP base URL, a Kafka cluster, a read-only PostgreSQL database, per system.
 * Everyone reads the paged list (filterable by system); an ADMIN creates, edits and deletes
 * through the editor modal. Passwords never travel back — the table shows which targets exist.
 */
export default function Environments() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = isAdmin();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [systemFilter, setSystemFilter] = useStoredState(`${SETTINGS_KEY}.filter.system`, "", isString);
  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const activeFilterCount = (nameFilter.trim() ? 1 : 0) + (systemFilter ? 1 : 0);
  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, systemFilter], { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const systemOptions = (systems.data?.items ?? []).map((s) => ({ value: String(s.id), label: `${s.domainName} / ${s.name}` }));

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["environments", "list", page, pageSize, sortParam, debouncedName, systemFilter],
    queryFn: () =>
      listEnvironments({ page, pageSize, sort: sortParam, name: debouncedName || undefined, systemId: systemFilter ? Number(systemFilter) : undefined }),
    placeholderData: keepPreviousData,
  });

  const [editorTarget, setEditorTarget] = useState<EnvironmentResponse | "new" | null>(null);
  const remove = useDeleteConfirm<EnvironmentResponse>({
    mutationFn: (row) => deleteEnvironment(row.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["environments"] });
    },
    successMessage: t("environments.toast.deleted"),
  });
  const columnCount = admin ? 5 : 4;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("environments.title")}
        description={t("environments.intro")}
        actions={
          admin && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")} disabled={systemOptions.length === 0}>
              {t("environments.newEnvironment")}
            </Button>
          )
        }
      />
      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput label={t("common.field.name")} value={nameFilter} onChange={setNameFilter} clearLabel={t("common.filter.clearName")} />
        <Select
          label={t("environments.field.system")}
          placeholder={t("common.state.any")}
          data={systemOptions}
          value={systemFilter || null}
          onChange={(v) => setSystemFilter(v ?? "")}
          clearable
          clearButtonProps={{ "aria-label": t("environments.clearSystemFilter") }}
          searchable
        />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("environments.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="systemId" label={t("environments.field.system")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("environments.column.targets")}</Table.Th>
            <Table.Th>{t("common.field.description")}</Table.Th>
            {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((env) => (
              <Table.Tr key={env.id}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {env.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{env.systemName}</Text>
                </Table.Td>
                <Table.Td>
                  <Group gap={4}>
                    {env.httpBaseUrl && <Badge variant="light" color="gray">{t("environments.target.http")}</Badge>}
                    {env.kafka && <Badge variant="light" color="gray">{t("environments.target.kafka")}</Badge>}
                    {env.postgres && <Badge variant="light" color="gray">{t("environments.target.postgres")}</Badge>}
                  </Group>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={env.description ? undefined : "dimmed"} lineClamp={1}>
                    {env.description ?? "—"}
                  </Text>
                </Table.Td>
                {admin && (
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: env.name })}>
                      <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(env)} aria-label={t("common.action.editAria", { name: env.name })}>
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(env)} aria-label={t("common.action.deleteAria", { name: env.name })}>
                        {t("common.action.delete")}
                      </Menu.Item>
                    </RowActionsMenu>
                  </Table.Td>
                )}
              </Table.Tr>
            ))
          ) : !isError ? (
            <Table.Tr>
              <Table.Td colSpan={columnCount}>
                <EmptyState icon={IconPlugConnected} label={systemOptions.length === 0 && admin ? t("environments.emptyNoSystems") : t("environments.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />
      {editorTarget !== null && (
        <EnvironmentEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          systemOptions={systemOptions}
          defaultSystemId={systemFilter || null}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["environments"] });
          }}
        />
      )}
      <ConfirmDeleteModal
        confirm={remove}
        title={t("environments.deleteTitle")}
        errorTitle={t("environments.deleteFailed")}
        errorMessage={(err) => saveErrorMessage(err, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })}
        body={(env) => t("environments.deleteBody", { name: env.name, system: env.systemName })}
      />
    </Stack>
  );
}
