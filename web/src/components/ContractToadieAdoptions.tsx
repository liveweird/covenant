import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Anchor, Badge, Group, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconExternalLink, IconListDetails } from "@tabler/icons-react";
import { getContractToadieAdoptions } from "../api/toadie";
import { usePagedSort } from "../hooks/usePagedSort";
import { loadErrorMessage } from "../utils/saveError";
import { formatDateTime } from "../utils/relativeTime";
import ClearableTextInput from "./ClearableTextInput";
import EmptyState from "./EmptyState";
import LoadingBlock from "./LoadingBlock";
import PaginationBar from "./PaginationBar";
import SortHeader from "./SortHeader";

const SORT_FIELDS = ["title", "id"] as const;
type SortField = (typeof SORT_FIELDS)[number];

export default function ContractToadieAdoptions({ contractId, onFetchingChange }: { contractId: number; onFetchingChange?: (fetching: boolean) => void }) {
  const { t, i18n } = useTranslation();
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const paging = usePagedSort<SortField>("title", [contractId, debouncedSearch], { key: "contractToadieAdoptions", sortFields: SORT_FIELDS });
  const query = useQuery({
    queryKey: ["contracts", "toadie-adoptions", contractId, paging.page, paging.pageSize, paging.sortParam, debouncedSearch],
    queryFn: () => getContractToadieAdoptions(contractId, { page: paging.page, pageSize: paging.pageSize, sort: paging.sortParam, q: debouncedSearch || undefined }),
    placeholderData: keepPreviousData,
    refetchInterval: ({ state }) => state.data?.cache.refreshing ? 1500 : false,
  });
  useEffect(() => { onFetchingChange?.(query.isFetching); }, [onFetchingChange, query.isFetching]);

  const availability = query.data?.availability;
  const cache = query.data?.cache;
  const cacheIncomplete = cache != null && (cache.state !== "CURRENT" || cache.refreshing || cache.lastErrorCode != null);
  const stateKey = cache?.state === "NEVER_SYNCED" ? "neverSynced" : cache?.state.toLowerCase() as "current" | "stale" | "disabled" | "disconnected" | "unlinked" | undefined;
  return <Stack gap="sm" role="region" aria-label={t("toadie.adoptions.title")}>
    <Stack gap={2}>
      <Group gap="xs"><Text fw={600} size="lg">{t("toadie.adoptions.title")}</Text>{stateKey && <Badge color={cache?.state === "CURRENT" ? "teal" : cache?.state === "STALE" ? "orange" : "gray"} variant="light">{t(`toadie.status.${stateKey}`)}</Badge>}</Group>
      <Text size="sm" c="dimmed">{t("toadie.adoptions.intro")}</Text>
    </Stack>
    {query.isLoading && !query.data && <LoadingBlock />}
    {query.isError && <Alert color="red" variant="light" title={t("toadie.adoptions.loadFailed")}>{loadErrorMessage(query.error, t)}</Alert>}
    {availability && availability !== "AVAILABLE" && <Alert color={availability === "NOT_CONFIGURED" ? "gray" : "orange"} variant="light">{t(`toadie.adoptions.availability.${availability}`)}</Alert>}
    {cacheIncomplete && <Alert color="orange" variant="light">{cache?.refreshing ? t("toadie.adoptions.refreshingWarning") : t("toadie.adoptions.incompleteWarning")}</Alert>}
    {cache?.lastSuccessAt != null && <Text size="sm" c="dimmed">{t("toadie.adoptions.sourceSnapshot", { time: formatDateTime(cache.lastSuccessAt, i18n.language) })}</Text>}
    {availability === "AVAILABLE" && <>
      <ClearableTextInput label={t("toadie.adoptions.search")} value={search} onChange={setSearch} clearLabel={t("common.filter.clearName")} />
      <Table.ScrollContainer minWidth={900}>
        <Table aria-label={t("toadie.adoptions.title")}>
          <Table.Thead><Table.Tr>
            <SortHeader field="title" label={t("toadie.adoptions.declaration")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
            <Table.Th>{t("toadie.adoptions.consumer")}</Table.Th><Table.Th>{t("toadie.adoptions.target")}</Table.Th><Table.Th>{t("toadie.adoptions.environment")}</Table.Th><Table.Th>{t("toadie.adoptions.value")}</Table.Th><Table.Th>{t("toadie.adoptions.status")}</Table.Th><Table.Th>{t("toadie.adoptions.provenance")}</Table.Th>
          </Table.Tr></Table.Thead>
          <Table.Tbody>
            {query.data?.items.map((row) => <Table.Tr key={row.id}>
              <Table.Td>{entity(row.title, row.identifier, row.url)}{!row.matchesConsumption && <Badge mt={4} py={4} h="auto" color="orange" variant="light" styles={{ label: { whiteSpace: "normal" } }}>{t("toadie.adoptions.noConsumptionEdge")}</Badge>}</Table.Td>
              <Table.Td>{entity(row.consumer.title, row.consumer.identifier, row.consumer.url)}</Table.Td>
              <Table.Td>{entity(row.target.title, row.target.identifier, row.target.url)}</Table.Td>
              <Table.Td>{row.environmentScope === "SPECIFIC" && row.environment ? entity(row.environment.title, row.environment.identifier, row.environment.url) : <Text size="sm" c={row.environmentScope === "UNKNOWN" ? "dimmed" : undefined}>{row.environmentScope === "ALL" ? t("toadie.adoptions.allEnvironments") : t("toadie.adoptions.unknownEnvironment")}</Text>}</Table.Td>
              <Table.Td><Text size="xs" c="dimmed">{row.kind === "API_MAJOR_LINE" ? t("toadie.adoptions.apiMajorLine") : t("toadie.adoptions.datasetContractVersion")}</Text><Text size="sm" c={row.value == null ? "dimmed" : undefined}>{row.value ?? t("toadie.adoptions.unknown")}</Text></Table.Td>
              <Table.Td><Text size="sm">{statusLabel(row.status)}</Text></Table.Td>
              <Table.Td><Text size="sm">{row.declaredBy ?? t("toadie.adoptions.notProvided")}</Text>{row.verifiedAt != null && <Text size="xs" c="dimmed">{t("toadie.adoptions.verifiedAt", { time: formatDateTime(row.verifiedAt, i18n.language) })}</Text>}{row.notes && <Text size="xs" style={{ whiteSpace: "pre-wrap" }}>{row.notes}</Text>}</Table.Td>
            </Table.Tr>)}
            {!query.isLoading && !query.isError && query.data?.items.length === 0 && <Table.Tr><Table.Td colSpan={7}><EmptyState icon={IconListDetails} label={cacheIncomplete ? t("toadie.adoptions.emptyIncomplete") : t("toadie.adoptions.empty")} /></Table.Td></Table.Tr>}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      <PaginationBar total={query.data?.total ?? 0} page={paging.page} pageSize={paging.pageSize} onPageChange={paging.setPage} onPageSizeChange={paging.setPageSize} />
    </>}
  </Stack>;

  function statusLabel(status: string | null): string {
    if (status == null || status === "") return t("toadie.adoptions.notProvided");
    switch (status.toLowerCase()) {
      case "current": return t("toadie.adoptions.knownStatus.current");
      case "migrating": return t("toadie.adoptions.knownStatus.migrating");
      case "retiring": return t("toadie.adoptions.knownStatus.retiring");
      default: return status;
    }
  }

  function entity(title: string, identifier: string, url: string | null) {
    return <>{url ? <Anchor href={url} target="_blank" rel="noreferrer" size="sm" aria-label={t("toadie.usage.openEntity", { name: title })}>{title}<IconExternalLink size={12} style={{ marginInlineStart: 4 }} /></Anchor> : <Text size="sm">{title}</Text>}<Text size="xs" c="dimmed">{identifier}</Text></>;
  }
}
