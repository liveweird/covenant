import { refreshQueriesAfterMutation } from "../utils/queryRefresh";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Badge, Button, Group, Menu, Select, Stack, Table, Text } from "@mantine/core";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconPlugConnected, IconPlus, IconTrash } from "@tabler/icons-react";
import { deleteEnvironment, listEnvironments, type EnvironmentResponse } from "../api/environments";
import { useAdmin } from "../auth";
import { listAllSystems } from "../api/systems";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EnvironmentEditorModal from "../components/EnvironmentEditorModal";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import RegistryListTable from "../components/RegistryListTable";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useRegistryListControls } from "../hooks/useRegistryListControls";
import { isString, useStoredState } from "../hooks/useStoredState";
import { saveErrorMessage } from "../utils/saveError";

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
  const admin = useAdmin();
  const [systemFilter, setSystemFilter] = useStoredState(`${SETTINGS_KEY}.filter.system`, "", isString);
  const { nameFilter, setNameFilter, debouncedName, nameFilterActive, page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    useRegistryListControls<SortField>({
      settingsKey: SETTINGS_KEY,
      sortFields: SORT_FIELDS,
      initialSortField: "name",
      extraFilterDeps: [systemFilter],
    });
  const activeFilterCount = (nameFilterActive ? 1 : 0) + (systemFilter ? 1 : 0);

  const systems = useQuery({ queryKey: ["systems", "all-pages"], queryFn: () => listAllSystems() });
  const systemOptions = (systems.data ?? []).map((s) => ({ value: String(s.id), label: `${s.domainName} / ${s.name}` }));

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["environments", "list", page, pageSize, sortParam, debouncedName, systemFilter],
    queryFn: () =>
      listEnvironments({ page, pageSize, sort: sortParam, name: debouncedName || undefined, systemId: systemFilter ? Number(systemFilter) : undefined }),
    placeholderData: keepPreviousData,
  });

  const [editorTarget, setEditorTarget] = useState<EnvironmentResponse | "new" | null>(null);

  const remove = useDeleteConfirm<EnvironmentResponse>({
    mutationFn: (row) => deleteEnvironment(row.id),
    onSuccess: () => refreshQueriesAfterMutation(queryClient, ["environments"]),
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
      <RegistryListTable
        errorTitle={t("environments.loadFailed")}
        error={error}
        isError={isError}
        isLoading={isLoading}
        hasData={Boolean(data)}
        rowCount={data?.items.length ?? 0}
        columnCount={columnCount}
        emptyIcon={IconPlugConnected}
        emptyLabel={systemOptions.length === 0 && admin ? t("environments.emptyNoSystems") : t("environments.empty")}
        header={
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="systemId" label={t("environments.field.system")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("environments.column.targets")}</Table.Th>
            <Table.Th>{t("common.field.description")}</Table.Th>
            {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
          </Table.Tr>
        }
        rows={data?.items.map((env) => (
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
        ))}
        total={data?.total ?? 0}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onPageSizeChange={setPageSize}
      />
      {editorTarget !== null && (
        <EnvironmentEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          systemOptions={systemOptions}
          defaultSystemId={systemFilter || null}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await refreshQueriesAfterMutation(queryClient, ["environments"]);
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
