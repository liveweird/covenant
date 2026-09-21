import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Badge, Button, Checkbox, Group, Modal, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconX } from "@tabler/icons-react";
import { listToadieApis, listToadieConnections, updateContractToadieLinks, type ToadieEntityRef, type ToadieLinks } from "../api/toadie";
import ClearableTextInput from "./ClearableTextInput";
import PaginationBar from "./PaginationBar";
import TableLoadingRow from "./TableLoadingRow";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";

type SelectedApi = ToadieEntityRef & { status?: string };

export default function ToadieLinksModal({ contractId, links, onClose, onSaved }: {
  contractId: number;
  links: ToadieLinks;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [connectionId, setConnectionId] = useState<string | null>(links.connection ? String(links.connection.id) : null);
  const [selected, setSelected] = useState<Map<string, SelectedApi>>(() => new Map(links.items.map((item) => [item.apiEntityId, {
    entityId: item.apiEntityId,
    identifier: item.identifier,
    title: item.title,
    url: item.url,
    status: item.status,
  }])));
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const connections = useQuery({ queryKey: ["toadie", "connections", "picker"], queryFn: () => listToadieConnections({ page: 1, pageSize: 20, sort: "name" }) });
  const apis = useQuery({
    queryKey: ["toadie", "apis", connectionId, page, pageSize, debouncedSearch],
    queryFn: async () => ({
      connectionId,
      page: await listToadieApis(Number(connectionId), { page, pageSize, q: debouncedSearch || undefined }),
    }),
    enabled: connectionId != null,
    placeholderData: keepPreviousData,
  });
  // `keepPreviousData` prevents table layout churn while paging/searching, but after a
  // connection switch those rows belong to another ID namespace. Never render them as choices:
  // two Toadie instances may legitimately use the same entity ID for different APIs.
  const apiPage = apis.data?.connectionId === connectionId ? apis.data.page : null;

  function chooseConnection(value: string | null) {
    setConnectionId(value);
    setPage(1);
    setSearch("");
    if (value !== String(links.connection?.id ?? "")) setSelected(new Map());
    else setSelected(new Map(links.items.map((item) => [item.apiEntityId, { entityId: item.apiEntityId, identifier: item.identifier, title: item.title, url: item.url, status: item.status }])));
  }

  function toggle(api: ToadieEntityRef, checked: boolean) {
    setSelected((current) => {
      const next = new Map(current);
      if (checked) next.set(api.entityId, api);
      else next.delete(api.entityId);
      return next;
    });
  }

  async function save() {
    setSubmitting(true);
    setError(null);
    try {
      await updateContractToadieLinks(contractId, {
        connectionId: connectionId == null ? null : Number(connectionId),
        // A disconnected mapping can only be cleared. Never send the invalid null + ids shape,
        // even if stale UI state somehow survives a connection change.
        apiEntityIds: connectionId == null ? [] : [...selected.keys()],
      });
      await onSaved();
    } catch (caught) {
      setError(saveErrorMessage(caught, t, { forbidden: "contracts.saveForbidden", invalid: "toadie.error.invalid", failedStatus: "common.error.saveFailedStatus", failed: "common.error.saveFailedNetwork" }));
      setSubmitting(false);
    }
  }

  return (
    <Modal opened onClose={onClose} closeButtonProps={{ "aria-label": t("common.action.close") }} title={t("toadie.usage.editLinks")} size="xl" centered>
      <Stack>
        <Select
          label={t("toadie.usage.connection")}
          placeholder={t("toadie.usage.selectConnection")}
          data={(connections.data?.items ?? []).map((row) => ({ value: String(row.id), label: row.name }))}
          value={connectionId}
          onChange={chooseConnection}
          clearable
          searchable
        />
        <Text fw={600} size="sm">{t("toadie.usage.selected", { count: selected.size })}</Text>
        {selected.size > 0 && <Group gap="xs">
          {[...selected.values()].map((api) => <Button key={api.entityId} variant="default" size="compact-sm" rightSection={<IconX size={12} />} aria-label={t("toadie.usage.remove", { name: api.title })} onClick={() => toggle(api, false)}>
            {api.title}
            {api.status === "MISSING" ? ` · ${t("toadie.usage.missing")}` : api.status === "DISCONNECTED" ? ` · ${t("toadie.status.disconnected")}` : ""}
          </Button>)}
        </Group>}
        {connectionId && <>
          <ClearableTextInput label={t("toadie.usage.searchApis")} value={search} onChange={(value) => { setSearch(value); setPage(1); }} clearLabel={t("toadie.usage.clearSearch")} />
          {apis.isError && <Alert color="red" variant="light">{loadErrorMessage(apis.error, t)}</Alert>}
          <Table aria-label={t("toadie.usage.availableApis")}>
            <Table.Thead><Table.Tr><Table.Th style={{ width: 1 }} /><Table.Th>{t("common.field.name")}</Table.Th><Table.Th>{t("toadie.usage.identifier")}</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {apis.isLoading || apiPage == null ? <TableLoadingRow colSpan={3} /> : apiPage.items.map((api) => <Table.Tr key={api.entityId}>
                <Table.Td><Checkbox aria-label={api.title} checked={selected.has(api.entityId)} onChange={(event) => toggle(api, event.currentTarget.checked)} /></Table.Td>
                <Table.Td><Text size="sm">{api.title}</Text></Table.Td>
                <Table.Td><Badge variant="light" color="gray">{api.identifier}</Badge></Table.Td>
              </Table.Tr>)}
            </Table.Tbody>
          </Table>
          <PaginationBar total={apiPage?.total ?? 0} page={page} pageSize={pageSize} onPageChange={setPage} onPageSizeChange={(size) => { setPageSize(size); setPage(1); }} />
        </>}
        {error && <Alert color="red" variant="light" title={t("toadie.usage.saveFailed")}>{error}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose} disabled={submitting}>{t("common.action.cancel")}</Button>
          <Button onClick={() => void save()} loading={submitting} disabled={connectionId == null ? selected.size > 0 : selected.size === 0}>{t("toadie.usage.saveLinks")}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
