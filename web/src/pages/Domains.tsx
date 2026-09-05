import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Menu, Modal, Stack, Table, Text, Textarea, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconFolders, IconPencil, IconPlus, IconTrash } from "@tabler/icons-react";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import { createDomain, deleteDomain, listDomains, updateDomain, type DomainResponse } from "../api/domains";
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
  EMPTY_REGISTRY_FORM,
  MAX_REGISTRY_DESCRIPTION_LENGTH,
  MAX_REGISTRY_NAME_LENGTH,
  registryDescription,
  registryFormValidation,
  registrySaveErrorMessage,
  type RegistryFormValues,
} from "../utils/registryForm";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SORT_FIELDS = ["name", "updatedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];
const SETTINGS_KEY = "domains";

/**
 * The domain registry (`/domains`) — the top of the Domain → System → Contract hierarchy.
 * Everyone reads the paged list; an ADMIN creates, renames and deletes (a modal per domain,
 * the registry shape). A domain still holding systems refuses deletion (the 409 names it).
 */
export default function Domains() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const admin = isAdmin();
  const [nameFilter, setNameFilter] = useStoredState(`${SETTINGS_KEY}.filter.name`, "", isString);
  const [debouncedName] = useDebouncedValue(nameFilter, 300);

  const { page, setPage, pageSize, setPageSize, sortField, sortDir, sortParam, toggleSort } =
    usePagedSort<SortField>("name", [debouncedName], { key: SETTINGS_KEY, sortFields: SORT_FIELDS });

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["domains", "list", page, pageSize, sortParam, debouncedName],
    queryFn: () => listDomains({ page, pageSize, sort: sortParam, name: debouncedName || undefined }),
    placeholderData: keepPreviousData,
  });

  const [editorTarget, setEditorTarget] = useState<DomainResponse | "new" | null>(null);
  const remove = useDeleteConfirm<DomainResponse>({
    mutationFn: (row) => deleteDomain(row.id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["domains"] }),
    successMessage: t("domains.toast.deleted"),
  });

  const columnCount = admin ? 4 : 3;

  return (
    <Stack gap="md">
      <PageHeader
        title={t("domains.title")}
        description={t("domains.intro")}
        actions={
          admin && (
            <Button leftSection={<IconPlus size={16} />} onClick={() => setEditorTarget("new")}>
              {t("domains.newDomain")}
            </Button>
          )
        }
      />
      <FilterPanel activeFilterCount={nameFilter.trim() ? 1 : 0} storageKey={SETTINGS_KEY}>
        <ClearableTextInput label={t("common.field.name")} value={nameFilter} onChange={setNameFilter} clearLabel={t("common.filter.clearName")} />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("domains.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      <Table>
        <Table.Thead>
          <Table.Tr>
            <SortHeader field="name" label={t("common.field.name")} activeField={sortField} activeDir={sortDir} onToggle={toggleSort} />
            <Table.Th>{t("common.field.description")}</Table.Th>
            <Table.Th>{t("domains.column.systems")}</Table.Th>
            {admin && <Table.Th aria-label={t("common.table.operations")} style={{ width: 1 }} />}
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {isLoading && !data ? (
            <TableLoadingRow colSpan={columnCount} />
          ) : data && data.items.length > 0 ? (
            data.items.map((domain) => (
              <Table.Tr key={domain.id}>
                <Table.Td>
                  <Text size="sm" fw={500}>
                    {domain.name}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c={domain.description ? undefined : "dimmed"} lineClamp={1}>
                    {domain.description ?? "—"}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{domain.systemCount}</Text>
                </Table.Td>
                {admin && (
                  <Table.Td style={{ width: 1 }} ta="right">
                    <RowActionsMenu label={t("common.table.operationsAria", { name: domain.name })}>
                      <Menu.Item leftSection={<IconPencil size={14} />} onClick={() => setEditorTarget(domain)} aria-label={t("common.action.editAria", { name: domain.name })}>
                        {t("common.action.edit")}
                      </Menu.Item>
                      <Menu.Divider />
                      <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={() => remove.requestDelete(domain)} aria-label={t("common.action.deleteAria", { name: domain.name })}>
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
                <EmptyState icon={IconFolders} label={t("domains.empty")} />
              </Table.Td>
            </Table.Tr>
          ) : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={data?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={setPageSize} />

      {editorTarget !== null && (
        <DomainEditorModal
          target={editorTarget === "new" ? null : editorTarget}
          onClose={() => setEditorTarget(null)}
          onSaved={async () => {
            setEditorTarget(null);
            await queryClient.invalidateQueries({ queryKey: ["domains"] });
          }}
        />
      )}
      <ConfirmDeleteModal
        confirm={remove}
        title={t("domains.deleteTitle")}
        errorTitle={t("domains.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "domains.deleteHoldsSystems", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(domain) => t("domains.deleteBody", { name: domain.name })}
      />
    </Stack>
  );
}

/** Create (target null) / edit (target set) — one modal, the same field block. */
function DomainEditorModal({ target, onClose, onSaved }: { target: DomainResponse | null; onClose: () => void; onSaved: () => Promise<void> }) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<RegistryFormValues>({
    initialValues: target ? { name: target.name, description: target.description ?? "" } : EMPTY_REGISTRY_FORM,
    validate: registryFormValidation(t, "domains"),
  });

  async function save(values: RegistryFormValues) {
    setError(null);
    setSubmitting(true);
    const body = { name: values.name.trim(), description: registryDescription(values.description) };
    try {
      if (target) {
        await updateDomain(target.id, body);
        showSuccessToast(t("domains.toast.saved"));
      } else {
        await createDomain(body);
        showSuccessToast(t("domains.toast.created"));
      }
      await onSaved();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("domains.saveConflict"));
      else setError(registrySaveErrorMessage(err, t, "domains"));
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={onClose} title={target ? t("domains.editTitle") : t("domains.createTitle")} centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
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
