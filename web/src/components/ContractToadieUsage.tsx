import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Badge, Button, Group, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery, useQueryClient } from "@tanstack/react-query";
import { IconBrandDatabricks, IconExternalLink, IconLink, IconRefresh } from "@tabler/icons-react";
import { getContractToadieLinks, getContractToadieUsage, refreshContractToadieUsage } from "../api/toadie";
import { ApiError } from "../api/http";
import { usePagedSort } from "../hooks/usePagedSort";
import ClearableTextInput from "./ClearableTextInput";
import EmptyState from "./EmptyState";
import PaginationBar from "./PaginationBar";
import SortHeader from "./SortHeader";
import TableLoadingRow from "./TableLoadingRow";
import ToadieLinksModal from "./ToadieLinksModal";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { formatDateTime } from "../utils/relativeTime";

const SORT_FIELDS = ["title", "id"] as const;
type SortField = (typeof SORT_FIELDS)[number];

export default function ContractToadieUsage({ contractId, canWrite }: { contractId: number; canWrite: boolean }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [role, setRole] = useState<"PROVIDER" | "CONSUMER" | null>(null);
  const [editing, setEditing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);
  const paging = usePagedSort<SortField>("title", [contractId, debouncedSearch, role], { key: "contractToadieUsage", sortFields: SORT_FIELDS });
  const links = useQuery({
    queryKey: ["contracts", "toadie-links", contractId],
    queryFn: () => getContractToadieLinks(contractId),
    refetchInterval: ({ state }) => state.data?.cache.refreshing ? 1500 : false,
  });
  const usage = useQuery({
    queryKey: ["contracts", "toadie-usage", contractId, paging.page, paging.pageSize, paging.sortParam, debouncedSearch, role],
    queryFn: () => getContractToadieUsage(contractId, { page: paging.page, pageSize: paging.pageSize, sort: paging.sortParam, q: debouncedSearch || undefined, role: role ?? undefined }),
    placeholderData: keepPreviousData,
    refetchInterval: ({ state }) => state.data?.cache.refreshing ? 1500 : false,
  });
  const cache = usage.data?.cache ?? links.data?.cache;

  async function refresh() {
    setRefreshError(null);
    try {
      await refreshContractToadieUsage(contractId);
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
    } catch (error) {
      setRefreshError(saveErrorMessage(error, t, { failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" }));
    }
  }

  const stateKey = cache?.state === "NEVER_SYNCED" ? "neverSynced" : cache?.state.toLowerCase() as "current" | "stale" | "disabled" | "disconnected" | "unlinked" | undefined;
  const stateColor = cache?.state === "CURRENT" ? "teal" : cache?.state === "STALE" ? "orange" : "gray";
  const usageError = usage.error instanceof ApiError && usage.error.status === 404 ? null : usage.error;
  return (
    <Stack gap="sm" role="region" aria-label={t("toadie.usage.title")}>
      <Group justify="space-between" align="flex-start">
        <Stack gap={2}>
          <Group gap="xs"><Text fw={600} size="lg">{t("toadie.usage.title")}</Text>{stateKey && <Badge color={stateColor} variant="light">{t(`toadie.status.${stateKey}`)}</Badge>}</Group>
          <Text size="sm" c="dimmed">{t("toadie.usage.intro")}</Text>
        </Stack>
        {canWrite && <Group gap="xs">
          <Button variant="default" leftSection={<IconLink size={16} />} onClick={() => setEditing(true)} disabled={!links.data}>{t("toadie.usage.editLinks")}</Button>
          <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => void refresh()} disabled={!links.data?.connection || cache?.refreshing} loading={cache?.refreshing}>{cache?.refreshing ? t("toadie.usage.refreshing") : t("toadie.usage.refresh")}</Button>
        </Group>}
      </Group>
      {cache?.lastSuccessAt != null && <Text size="sm" c="dimmed">{t("toadie.usage.lastRefreshed", { time: formatDateTime(cache.lastSuccessAt, i18n.language) })}</Text>}
      {cache?.lastErrorCode && <Alert color="orange" variant="light">{t("toadie.lastError", { code: cache.lastErrorCode })}</Alert>}
      {(links.isError || usage.isError || refreshError) && <Alert color="red" variant="light" title={t("toadie.usage.loadFailed")}>{refreshError ?? loadErrorMessage(links.error ?? usageError, t)}</Alert>}
      {links.data?.items.length ? <Group gap="xs"><Text size="sm" fw={500}>{t("toadie.usage.links")}:</Text>{links.data.items.map((link) => link.status === "AVAILABLE" && link.url ? <Anchor key={link.id} href={link.url} target="_blank" rel="noreferrer" size="sm" aria-label={t("toadie.usage.openEntity", { name: link.title })}>{link.title}<IconExternalLink size={12} style={{ marginInlineStart: 4 }} /></Anchor> : <Badge key={link.id} color={link.status === "AVAILABLE" ? "gray" : "orange"} variant="light">{link.title} · {link.status === "MISSING" ? t("toadie.usage.missing") : t("toadie.status.disconnected")}</Badge>)}</Group> : links.data && <Text size="sm" c="dimmed">{t("toadie.usage.noLinks")}</Text>}
      <Group align="flex-end">
        <ClearableTextInput label={t("toadie.usage.service")} value={search} onChange={setSearch} clearLabel={t("common.filter.clearName")} />
        <Select label={t("toadie.usage.role")} value={role} onChange={(value) => setRole(value as typeof role)} placeholder={t("toadie.usage.anyRole")} clearable data={[{ value: "PROVIDER", label: t("toadie.usage.provider") }, { value: "CONSUMER", label: t("toadie.usage.consumer") }]} />
      </Group>
      <Table aria-label={t("toadie.usage.title")}>
        <Table.Thead><Table.Tr>
          <SortHeader field="title" label={t("toadie.usage.service")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th>{t("toadie.usage.role")}</Table.Th><Table.Th>{t("toadie.usage.systems")}</Table.Th><Table.Th>{t("toadie.usage.teams")}</Table.Th><Table.Th>{t("toadie.usage.version")}</Table.Th><Table.Th>{t("toadie.usage.releaseLine")}</Table.Th>
        </Table.Tr></Table.Thead>
        <Table.Tbody>
          {usage.isLoading && !usage.data ? <TableLoadingRow colSpan={6} /> : usage.data?.items.length ? usage.data.items.map((row) => <Table.Tr key={row.id}>
            <Table.Td>{row.url ? <Anchor href={row.url} target="_blank" rel="noreferrer" aria-label={t("toadie.usage.openEntity", { name: row.title })}>{row.title}<IconExternalLink size={12} style={{ marginInlineStart: 4 }} /></Anchor> : <Text size="sm">{row.title}</Text>}<Text size="xs" c="dimmed">{row.identifier}</Text></Table.Td>
            <Table.Td><Group gap={4}>{row.roles.map((item) => <Badge key={item} variant="light" color={item === "PROVIDER" ? "teal" : "gray"}>{item === "PROVIDER" ? t("toadie.usage.provider") : t("toadie.usage.consumer")}</Badge>)}</Group></Table.Td>
            <Table.Td>{row.systems.length ? row.systems.map((item) => item.url ? <Anchor key={item.entityId} href={item.url} target="_blank" rel="noreferrer" display="block" size="sm" aria-label={t("toadie.usage.openEntity", { name: item.title })}>{item.title}</Anchor> : <Text key={item.entityId} size="sm">{item.title}</Text>) : "—"}</Table.Td>
            <Table.Td>{row.teams.length ? row.teams.map((item) => item.url ? <Anchor key={item.entityId} href={item.url} target="_blank" rel="noreferrer" display="block" size="sm" aria-label={t("toadie.usage.openEntity", { name: item.title })}>{item.title}</Anchor> : <Text key={item.entityId} size="sm">{item.title}</Text>) : "—"}</Table.Td>
            <Table.Td><Text size="sm" c={row.version == null ? "dimmed" : undefined}>{row.version ?? t("toadie.usage.unknown")}</Text></Table.Td>
            <Table.Td><Text size="sm" c={row.releaseLine == null ? "dimmed" : undefined}>{row.releaseLine ?? t("toadie.usage.unknown")}</Text></Table.Td>
          </Table.Tr>) : !usage.isError ? <Table.Tr><Table.Td colSpan={6}><EmptyState icon={IconBrandDatabricks} label={t("toadie.usage.empty")} /></Table.Td></Table.Tr> : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={usage.data?.total ?? 0} page={paging.page} pageSize={paging.pageSize} onPageChange={paging.setPage} onPageSizeChange={paging.setPageSize} />
      {editing && links.data && <ToadieLinksModal contractId={contractId} links={links.data} onClose={() => setEditing(false)} onSaved={async () => { setEditing(false); await queryClient.invalidateQueries({ queryKey: ["contracts"] }); }} />}
    </Stack>
  );
}
