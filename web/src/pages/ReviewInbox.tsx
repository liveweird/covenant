import { Alert, Anchor, Badge, Button, Group, Stack, Table, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconMessageCheck, IconRefresh } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { getReviewInboxSummary, listReviewInbox, type ReviewInboxRow } from "../api/reviewInbox";
import { getUserId } from "../api/session";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import OwnerChip from "../components/OwnerChip";
import PageHeader from "../components/PageHeader";
import PaginationBar from "../components/PaginationBar";
import ReviewInboxFilters from "../components/ReviewInboxFilters";
import ReviewInboxSummary from "../components/ReviewInboxSummary";
import SortHeader from "../components/SortHeader";
import TableLoadingRow from "../components/TableLoadingRow";
import TypeBadge from "../components/TypeBadge";
import { useReviewInboxFilters } from "../hooks/useReviewInboxFilters";
import { usePagedSort } from "../hooks/usePagedSort";
import { versionReviewsPath } from "../utils/contractLinks";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

const SETTINGS_KEY = "reviewInbox";
const SORT_FIELDS = ["id", "contractName", "requestedAt"] as const;
type SortField = (typeof SORT_FIELDS)[number];

export default function ReviewInbox() {
  const { t, i18n } = useTranslation();
  const callerId = getUserId();
  const filters = useReviewInboxFilters();
  const paging = usePagedSort<SortField>("requestedAt", filters.deps, { key: SETTINGS_KEY, sortFields: SORT_FIELDS });
  const summaryFilters = { ...filters.values, attention: undefined };
  const summary = useQuery({
    queryKey: ["contracts", "review-inbox", "summary", callerId, summaryFilters],
    queryFn: () => getReviewInboxSummary(summaryFilters),
    placeholderData: (previousData, previousQuery) => previousQuery?.queryKey[3] === callerId ? previousData : undefined,
  });
  const inbox = useQuery({
    queryKey: ["contracts", "review-inbox", "list", callerId, paging.page, paging.pageSize, paging.sortParam, filters.values],
    queryFn: () => listReviewInbox({ page: paging.page, pageSize: paging.pageSize, sort: paging.sortParam, ...filters.values }),
    placeholderData: (previousData, previousQuery) => previousQuery?.queryKey[3] === callerId ? previousData : undefined,
  });

  return <Stack gap="md">
    <PageHeader
      title={t("reviewInbox.title")}
      description={t("reviewInbox.intro")}
      actions={<Button
        variant="default"
        leftSection={<IconRefresh size={16} />}
        loading={inbox.isFetching || summary.isFetching}
        onClick={() => void Promise.all([inbox.refetch(), summary.refetch()])}
      >
        {t("reviewInbox.refresh")}
      </Button>}
    />
    {summary.isError && <Alert color="red" variant="light" title={t("reviewInbox.summaryLoadFailed")}>{loadErrorMessage(summary.error, t)}</Alert>}
    <ReviewInboxSummary summary={summary.data} selected={filters.values.attention} onSelect={(attention) => filters.slots.setAttention(attention ?? null)} />
    <FilterPanel activeFilterCount={filters.activeCount} storageKey={SETTINGS_KEY}>
      <ReviewInboxFilters filters={filters} />
    </FilterPanel>
    {inbox.isError && <Alert color="red" variant="light" title={t("reviewInbox.loadFailed")}>{loadErrorMessage(inbox.error, t)}</Alert>}
    <Table.ScrollContainer minWidth={1080}>
      <Table aria-label={t("reviewInbox.tableAria")}>
        <Table.Thead><Table.Tr>
          <SortHeader field="contractName" label={t("reviewInbox.column.contract")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th>{t("reviewInbox.column.version")}</Table.Th>
          <Table.Th>{t("reviewInbox.column.owner")}</Table.Th>
          <SortHeader field="requestedAt" label={t("reviewInbox.column.review")} activeField={paging.sortField} activeDir={paging.sortDir} onToggle={paging.toggleSort} />
          <Table.Th>{t("reviewInbox.column.attention")}</Table.Th>
          <Table.Th>{t("reviewInbox.column.decisions")}</Table.Th>
          <Table.Th aria-label={t("common.table.operations")} />
        </Table.Tr></Table.Thead>
        <Table.Tbody>
          {inbox.isLoading && !inbox.data ? <TableLoadingRow colSpan={7} /> : inbox.data?.items.length ? inbox.data.items.map((row) =>
            <InboxRow key={row.id} row={row} locale={i18n.language} />
          ) : !inbox.isError ? <Table.Tr><Table.Td colSpan={7}><EmptyState icon={IconMessageCheck} label={t("reviewInbox.empty")} /></Table.Td></Table.Tr> : null}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
    <PaginationBar total={inbox.data?.total ?? 0} page={paging.page} pageSize={paging.pageSize} onPageChange={paging.setPage} onPageSizeChange={paging.setPageSize} />
  </Stack>;
}

function InboxRow({ row, locale }: { row: ReviewInboxRow; locale: string }) {
  const { t } = useTranslation();
  return <Table.Tr>
    <Table.Td style={{ minWidth: 230 }}><Stack gap={2}>
      <Group gap="xs"><Anchor component={RouterLink} to={versionReviewsPath(row.contract.id, row.version.id)} fw={600} size="sm">{row.contract.name}</Anchor><TypeBadge type={row.contract.type} /></Group>
      <Text size="xs" c="dimmed">{row.contract.domain.name} · {row.contract.system.name}</Text>
    </Stack></Table.Td>
    <Table.Td><Text ff="monospace" fw={600}>{row.version.version}</Text></Table.Td>
    <Table.Td style={{ minWidth: 165 }}><OwnerChip owner={row.contract.owner} /></Table.Td>
    <Table.Td style={{ minWidth: 210 }}><Stack gap={2}>
      <Badge size="xs" variant="light" color={row.review.status === "OPEN" ? "yellow" : row.review.status === "OUTDATED" ? "orange" : "gray"}>{t(`versions.reviews.status.${row.review.status}`)}</Badge>
      <Text size="xs">{t("reviewInbox.requestedBy", {
        name: `${row.review.requestedBy.name}${row.review.requestedBy.deleted ? ` ${t("versions.reviews.deletedIdentity")}` : ""}`,
        date: formatDateTime(row.review.requestedAt, locale),
      })}</Text>
    </Stack></Table.Td>
    <Table.Td style={{ minWidth: 180 }}><Group gap={4}>
      {row.awaitingMyReview && <Badge color="yellow" variant="light" size="xs">{t("reviewInbox.attention.AWAITING_MY_REVIEW")}</Badge>}
      {row.changesRequested && <Badge color="orange" variant="light" size="xs">{t("reviewInbox.attention.CHANGES_REQUESTED")}</Badge>}
      {row.needsNewReview && <Badge color="gray" variant="light" size="xs">{t("reviewInbox.attention.NEEDS_NEW_REVIEW")}</Badge>}
      {!row.awaitingMyReview && !row.changesRequested && !row.needsNewReview && <Text size="sm" c="dimmed">{t("reviewInbox.noAttention")}</Text>}
    </Group></Table.Td>
    <Table.Td style={{ minWidth: 150 }}><Stack gap={2}>
      <Text size="sm">{t("reviewInbox.approvals", { count: row.review.approvalCount })}</Text>
      <Text size="sm">{t("reviewInbox.changeRequests", { count: row.review.changesRequestedCount })}</Text>
      {row.review.myDecision && <Badge size="xs" variant="light" color={row.review.myDecision === "APPROVED" ? "teal" : "orange"}>{t(`versions.reviews.entry.${row.review.myDecision}`)}</Badge>}
    </Stack></Table.Td>
    <Table.Td><Button component={RouterLink} to={versionReviewsPath(row.contract.id, row.version.id)} variant="default" size="xs"
      aria-label={t("reviewInbox.openAria", { contract: row.contract.name, version: row.version.version })}>
      {t("reviewInbox.open")}
    </Button></Table.Td>
  </Table.Tr>;
}
