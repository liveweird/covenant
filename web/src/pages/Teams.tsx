import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Anchor, Button, Menu, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconPlus, IconTrash, IconUsersGroup } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { deleteTeam, getTeam, listTeams, type TeamListItem, type TeamResponse } from "../api/teams";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TeamEditorModal from "../components/TeamEditorModal";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { teamPath } from "../utils/teamLinks";

const SORT_FIELDS = ["name", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

const SETTINGS_KEY = "teams";

/**
 * The Teams registry (`/teams`): everyone gets the paged read-only list (a team's name opens
 * its roster); an ADMIN additionally creates, renames and deletes teams (a modal per team —
 * the registry shape). Membership lives on the details page.
 */
export default function Teams() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const admin = isAdmin();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const activeFilterCount = nameFilter.trim() ? 1 : 0;

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName], { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["teams", "list", page, pageSize, sortParam, debouncedName],
    queryFn: () => listTeams({ page, pageSize, sort: sortParam, name: debouncedName || undefined }),
    placeholderData: keepPreviousData,
  });

  // The editor takes the DETAIL shape (it prefills from it); the list row only has the summary,
  // so Edit fetches the team first — through the detail query key, so the page shares the cache.
  const [editorTarget, setEditorTarget] = useState<TeamResponse | "new" | null>(null);
  async function openEditor(row: TeamListItem) {
    const team = await queryClient.fetchQuery({ queryKey: ["teams", "detail", row.id], queryFn: () => getTeam(row.id) });
    setEditorTarget(team);
  }

  const remove = useDeleteConfirm<TeamListItem>({
    mutationFn: (row) => deleteTeam(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["teams"] }),
    successMessage: t("teams.toast.deleted"),
  });

  const total = data?.total ?? 0;
  const columnCount = admin ? 4 : 3;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("teams.title")}
        description={t("teams.intro")}
        actions={
          admin && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
              {t("teams.newTeam")}
            </Button>
          )
        }
      />

      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput
          label={t("common.field.name")}
          value={nameFilter}
          onChange={setNameFilter}
          clearLabel={t("common.filter.clearName")}
        />
      </FilterPanel>

      {isError && (
        <Alert color="red" variant="light" title={t("teams.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}

      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("common.field.description")}</Table.Th>
            <Table.Th>{t("teams.column.members")}</Table.Th>
            {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((team) => (
              <Table.Tr key={team.id}>
                <Table.Td>
                  {/* The name is the way into the roster — a real link (the detail-link rule). */}
                  <Anchor component={RouterLink} to={teamPath(team.id)} fw={500} size="sm" aria-label={t("teams.openAria", { name: team.name })}>
                    {team.name}
                  </Anchor>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={team.description ? undefined : "dimmed"} lineClamp={1}>
                    {team.description ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{team.memberCount}</Text>
                </Table.Td>
                {admin && (
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: team.name })}>
                      <Menu.Item leftSection={<IconUsersGroup size={14} />} onClick={() => navigate(teamPath(team.id))}>
                        {t("teams.manageMembers")}
                      </Menu.Item>
                      <Menu.Item
                        leftSection={<IconPencil size={14} />}
                        onClick={() => void openEditor(team)}
                        aria-label={t("common.action.editAria", { name: team.name })}
                      >
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item
                        color="red"
                        leftSection={<IconTrash size={14} />}
                        onClick={() => remove.requestDelete(team)}
                        aria-label={t("common.action.deleteAria", { name: team.name })}
                      >
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
                <EmptyState icon={IconUsersGroup} label={t("teams.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>

      <PaginationBar total={total} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />

      {editorTarget !== null && (
        <TeamEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async (saved) => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["teams"] });
            // A NEW team lands on its roster page, where the members get added.
            if (saved) navigate(teamPath(saved.id));
          }}
        />
      )}

      <ConfirmDeleteModal
        confirm={remove}
        title={t("teams.deleteTitle")}
        errorTitle={t("teams.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, {
            conflict: "teams.deleteOwnsContracts",
            failedStatus: "common.error.actionFailedStatus",
            failed: "common.error.actionFailed",
          })
        }
        body={(team) => t("teams.deleteBody", { name: team.name })}
      />
    </Stack>
  );
}
