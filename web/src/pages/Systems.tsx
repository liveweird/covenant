import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Menu, Modal, Select, Stack, Table, Text, Textarea, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconPencil, IconPlus, IconServer2, IconTrash } from "@tabler/icons-react";
import { listAllDomains } from "../api/domains";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import { createSystem, deleteSystem, listSystems, updateSystem, type SystemResponse } from "../api/systems";
import ClearableTextInput from "../components/ClearableTextInput";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import RowActionsMenu from "../components/RowActionsMenu";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { usePagedSort } from "../hooks/usePagedSort";
import { isString, useStoredState } from "../hooks/useStoredState";
import { charCountDescription } from "../utils/charCount";
import {
  MAX_REGISTRY_DESCRIPTION_LENGTH,
  MAX_REGISTRY_NAME_LENGTH,
  registryDescription,
  registryFormValidation,
  registrySaveErrorMessage,
} from "../utils/registryForm";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["name", "domainId", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "systems";

type SystemFormValues = { domainId: string | null; name: string; description: string };

/**
 * The system registry (`/systems`) — the applications exposing contracts, each inside one
 * domain. Everyone reads the paged list (filterable by domain); an ADMIN creates, edits (the
 * domain Select MOVES a system) and deletes through a modal per system.
 */
export default function Systems() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = isAdmin();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [domainFilter, setDomainFilter] = useStoredState(`${SETTINGS_KEY}.filter.domain`, "", isString);
  const [debouncedName] = useDebouncedValue(nameFilter, 300);
  const activeFilterCount = (nameFilter.trim() ? 1 : 0) + (domainFilter ? 1 : 0);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName, domainFilter], { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  const domains = useQuery({ queryKey: ["domains", "all"], queryFn: listAllDomains });
  const domainOptions = (domains.data ?? []).map((d) => ({ value: String(d.id), label: d.name }));

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["systems", "list", page, pageSize, sortParam, debouncedName, domainFilter],
    queryFn: () =>
      listSystems({ page, pageSize, sort: sortParam, name: debouncedName || undefined, domainId: domainFilter ? Number(domainFilter) : undefined }),
    placeholderData: keepPreviousData,
  });

  const [editorTarget, setEditorTarget] = useState<SystemResponse | "new" | null>(null);
  const remove = useDeleteConfirm<SystemResponse>({
    mutationFn: (row) => deleteSystem(row.id),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["systems"] });
      await queryClient.invalidateQueries({ queryKey: ["domains"] });
    },
    successMessage: t("systems.toast.deleted"),
  });

  const columnCount = admin ? 5 : 4;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("systems.title")}
        description={t("systems.intro")}
        actions={
          admin && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")} disabled={domainOptions.length === 0}>
              {t("systems.newSystem")}
            </Button>
          )
        }
      />
      <FilterPanel activeFilterCount={activeFilterCount} storageKey={SETTINGS_KEY}>
        <ClearableTextInput label={t("common.field.name")} value={nameFilter} onChange={setNameFilter} clearLabel={t("common.filter.clearName")} />
        <Select
          label={t("systems.field.domain")}
          placeholder={t("common.state.any")}
          data={domainOptions}
          value={domainFilter || null}
          onChange={(v) => setDomainFilter(v ?? "")}
          clearable
          clearButtonProps={{ "aria-label": t("systems.clearDomainFilter") }}
          searchable
        />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("systems.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <SortHeader field="domainId" label={t("systems.field.domain")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("common.field.description")}</Table.Th>
            <Table.Th>{t("systems.column.contracts")}</Table.Th>
            {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((system) => (
              <Table.Tr key={system.id}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {system.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{system.domainName}</Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={system.description ? undefined : "dimmed"} lineClamp={1}>
                    {system.description ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{system.contractCount}</Text>
                </Table.Td>
                {admin && (
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: system.name })}>
                      <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(system)} aria-label={t("common.action.editAria", { name: system.name })}>
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(system)} aria-label={t("common.action.deleteAria", { name: system.name })}>
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
                <EmptyState icon={IconServer2} label={domainOptions.length === 0 && admin ? t("systems.emptyNoDomains") : t("systems.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />

      {editorTarget !== null && (
        <SystemEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          domainOptions={domainOptions}
          defaultDomainId={domainFilter || null}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["systems"] });
            await queryClient.invalidateQueries({ queryKey: ["domains"] });
          }}
        />
      )}
      <ConfirmDeleteModal
        confirm={remove}
        title={t("systems.deleteTitle")}
        errorTitle={t("systems.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "systems.deleteHoldsContracts", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(system) => t("systems.deleteBody", { name: system.name, domain: system.domainName })}
      />
    </Stack>
  );
}

function SystemEditorModal({
  target,
  domainOptions,
  defaultDomainId,
  onClose,
  onSaved,
}: {
  target: SystemResponse | null;
  domainOptions: { value: string; label: string }[];
  defaultDomainId: string | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const rules = registryFormValidation(t, "systems");
  const form = useForm<SystemFormValues>({
    initialValues: target
      ? { domainId: String(target.domainId), name: target.name, description: target.description ?? "" }
      : { domainId: defaultDomainId, name: "", description: "" },
    validate: { ...rules, domainId: (v) => (v ? null : t("systems.validation.domainRequired")) },
  });

  async function save(values: SystemFormValues) {
    setError(null);
    setSubmitting(true);
    const body = { domainId: Number(values.domainId), name: values.name.trim(), description: registryDescription(values.description) };
    try {
      if (target) {
        await updateSystem(target.id, body);
        showSuccessToast(t("systems.toast.saved"));
      } else {
        await createSystem(body);
        showSuccessToast(t("systems.toast.created"));
      }
      await onSaved();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("systems.saveConflict"));
      else setError(registrySaveErrorMessage(err, t, "systems"));
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={onClose} title={target ? t("systems.editTitle") : t("systems.createTitle")} centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <Select
            label={t("systems.field.domain")}
            description={target ? t("systems.field.domainMoveHint") : undefined}
            data={domainOptions}
            searchable
            allowDeselect={false}
            {...form.getInputProps("domainId")}
          />
          <TextInput label={t("common.field.name")} maxLength={MAX_REGISTRY_NAME_LENGTH} data-autofocus {...form.getInputProps("name")} />
          <Textarea
            label={t("common.field.description")}
            autosize
            minRows={2}
            maxLength={MAX_REGISTRY_DESCRIPTION_LENGTH}
            description={charCountDescription(form.values.description.length, MAX_REGISTRY_DESCRIPTION_LENGTH)}
            inputWrapperOrder={["label", "input", "description", "error"]}
            {...form.getInputProps("description")}
          />
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            <Button type="button" variant="default" onClick={onClose} disabled={submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={submitting}>
              {target ? t("common.action.save") : t("common.action.create")}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
