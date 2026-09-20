import { useEffect, useRef, useState } from "react";
import { Accordion, Alert, Badge, Button, Group, Pagination, Paper, Stack, Text, Textarea } from "@mantine/core";
import { IconCheck, IconMessage, IconMessagePlus, IconRefresh, IconX } from "@tabler/icons-react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { useLocation } from "react-router-dom";
import type { Lifecycle } from "../api/contracts";
import { ApiError } from "../api/http";
import {
  createVersionReview,
  createVersionReviewEntry,
  listVersionReviewEntries,
  listVersionReviews,
  type VersionReviewEntryKind,
  type VersionReviewResponse,
} from "../api/versionReviews";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "./LoadingBlock";

const ROUND_PAGE_SIZE = 5;
const ENTRY_PAGE_SIZE = 10;

export default function VersionReviews({
  contractId,
  versionId,
  lifecycle,
  contentRevision,
  disabled,
}: {
  contractId: number;
  versionId: number;
  lifecycle: Lifecycle;
  contentRevision: number;
  disabled: boolean;
}) {
  const { t } = useTranslation();
  const location = useLocation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const autoSelectedPage = useRef<number | null>(null);
  const scrolledTarget = useRef<string | null>(null);
  const reviews = useQuery({
    queryKey: ["contracts", "version-reviews", contractId, versionId, contentRevision, page],
    queryFn: () => listVersionReviews(contractId, versionId, page, ROUND_PAGE_SIZE),
    placeholderData: keepPreviousData,
  });

  useEffect(() => {
    if (reviews.data?.page === page && autoSelectedPage.current !== page) {
      autoSelectedPage.current = page;
      setExpanded(reviews.data.items[0] ? String(reviews.data.items[0].id) : null);
    }
  }, [page, reviews.data]);

  useEffect(() => {
    if (location.hash !== "#reviews" || !reviews.data) return;
    const target = `${location.key}:${contractId}:${versionId}`;
    if (scrolledTarget.current === target) return;
    const frame = requestAnimationFrame(() => {
      const element = document.getElementById("reviews");
      if (!element) return;
      element.scrollIntoView({ block: "start" });
      scrolledTarget.current = target;
    });
    return () => cancelAnimationFrame(frame);
  }, [contractId, location.hash, location.key, reviews.data, versionId]);

  function markStale() {
    setStale(true);
  }

  const refresh = useMutation({
    mutationFn: async () => {
      await queryClient.refetchQueries(
        { queryKey: ["contracts", "version", contractId, versionId], type: "active" },
        { throwOnError: true },
      );
      await queryClient.refetchQueries(
        { queryKey: ["contracts", "version-reviews", contractId, versionId], type: "active" },
        { throwOnError: true },
      );
    },
    onSuccess: () => setStale(false),
  });

  const request = useMutation({
    mutationFn: () => createVersionReview(contractId, versionId, contentRevision),
    onSuccess: async (round) => {
      setStale(false);
      autoSelectedPage.current = 1;
      setPage(1);
      setExpanded(String(round.id));
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["contracts", "version-reviews", contractId, versionId] }),
        queryClient.invalidateQueries({ queryKey: ["contracts", "review-inbox"] }),
      ]);
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) markStale();
    },
  });

  const displayedIsCurrent = reviews.data?.currentContentRevision === contentRevision;
  const canRequest = reviews.data?.canRequest === true && displayedIsCurrent && !disabled && !stale && !refresh.isPending;

  return (
    <Paper id="reviews" withBorder p="md" radius="md" role="region" aria-label={t("versions.reviews.title")}
      style={{ scrollMarginTop: "calc(48px + var(--mantine-spacing-md))" }}>
      <Stack gap="md">
        <Group justify="space-between" align="flex-start">
          <div>
            <Text fw={600}>{t("versions.reviews.title")}</Text>
            <Text size="sm" c="dimmed">{t("versions.reviews.intro")}</Text>
          </div>
          {canRequest && (
            <Button leftSection={<IconMessagePlus size={16} />} onClick={() => request.mutate()} loading={request.isPending}>
              {t("versions.reviews.request")}
            </Button>
          )}
        </Group>
        {(stale || (reviews.data != null && !displayedIsCurrent)) && (
          <Alert color="orange" variant="light" title={t("versions.reviews.staleTitle")} icon={<IconRefresh size={16} />}>
            <Stack gap="xs" align="flex-start">
              <Text size="sm">{t("versions.reviews.staleBody")}</Text>
              <Button variant="light" color="orange" size="xs" leftSection={<IconRefresh size={14} />} loading={refresh.isPending} onClick={() => refresh.mutate()}>
                {t("versions.reviews.refresh")}
              </Button>
            </Stack>
          </Alert>
        )}
        {refresh.isError && (
          <Alert color="red" variant="light" title={t("versions.reviews.refreshFailed")}>
            {loadErrorMessage(refresh.error, t)}
          </Alert>
        )}
        {request.isError && !(request.error instanceof ApiError && request.error.status === 409) && (
          <Alert color="red" variant="light" title={t("versions.reviews.requestFailed")}>
            {loadErrorMessage(request.error, t)}
          </Alert>
        )}
        {reviews.isLoading && !reviews.data ? <LoadingBlock py="sm" /> : reviews.isError ? (
          <Alert color="red" variant="light" title={t("versions.reviews.loadFailed")}>
            {loadErrorMessage(reviews.error, t)}
          </Alert>
        ) : reviews.data?.items.length ? (
          <>
            <Accordion value={expanded} onChange={setExpanded} variant="separated">
              {reviews.data.items.map((round) => (
                <ReviewRound
                  key={`${round.id}:${contentRevision}`}
                  round={round}
                  expanded={expanded === String(round.id)}
                  displayedContentRevision={contentRevision}
                  currentContentRevision={reviews.data.currentContentRevision}
                  disabled={disabled || stale || refresh.isPending}
                  onStale={markStale}
                />
              ))}
            </Accordion>
            {reviews.data.total > ROUND_PAGE_SIZE && (
              <Group justify="center">
                <Pagination value={page} onChange={(next) => {
                  autoSelectedPage.current = null;
                  setExpanded(null);
                  setPage(next);
                }} total={Math.ceil(reviews.data.total / ROUND_PAGE_SIZE)} size="sm"
                  getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })} />
              </Group>
            )}
          </>
        ) : (
          <Text size="sm" c="dimmed">
            {lifecycle === "PROPOSED" ? t("versions.reviews.emptyProposed") : t("versions.reviews.empty")}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

function ReviewRound({
  round,
  expanded,
  displayedContentRevision,
  currentContentRevision,
  disabled,
  onStale,
}: {
  round: VersionReviewResponse;
  expanded: boolean;
  displayedContentRevision: number;
  currentContentRevision: number;
  disabled: boolean;
  onStale: () => void;
}) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [page, setPage] = useState(1);
  const [body, setBody] = useState("");
  const entries = useQuery({
    queryKey: ["contracts", "version-review-entries", round.id, page],
    queryFn: () => listVersionReviewEntries(round.id, page, ENTRY_PAGE_SIZE),
    enabled: expanded,
    placeholderData: keepPreviousData,
  });
  const mutation = useMutation({
    mutationFn: (kind: VersionReviewEntryKind) => createVersionReviewEntry(round.id, {
      expectedContentRevision: displayedContentRevision,
      kind,
      ...(body.trim() ? { body: body.trim() } : {}),
    }),
    onSuccess: async () => {
      setBody("");
      setPage(1);
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ["contracts", "version-review-entries", round.id] }),
        queryClient.invalidateQueries({ queryKey: ["contracts", "version-reviews", round.contractId, round.versionId] }),
        queryClient.invalidateQueries({ queryKey: ["contracts", "review-inbox"] }),
      ]);
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 409) onStale();
    },
  });
  const safeToAct = round.status === "OPEN"
    && round.isCurrentContent
    && round.contentRevision === displayedContentRevision
    && currentContentRevision === displayedContentRevision
    && !disabled;
  const statusColor = round.status === "OPEN" ? "yellow" : round.status === "OUTDATED" ? "orange" : "gray";
  const commentReady = body.trim().length > 0 && body.trim().length <= 4000;

  return (
    <Accordion.Item value={String(round.id)}>
      <Accordion.Control>
        <Group justify="space-between" wrap="wrap" pr="sm">
          <Stack gap={1}>
            <Text size="sm" fw={600}>{t("versions.reviews.round", { id: round.id })}</Text>
            <Text size="xs" c="dimmed">
              {t("versions.reviews.requestedBy", { name: round.requestedBy.name, deleted: round.requestedBy.deleted ? t("versions.reviews.deletedIdentity") : "", date: formatDateTime(round.requestedAt, i18n.language) })}
            </Text>
          </Stack>
          <Group gap="xs">
            <Badge color="teal" variant="light">{t("versions.reviews.approvalCount", { count: round.approvalCount })}</Badge>
            <Badge color="orange" variant="light">{t("versions.reviews.changesCount", { count: round.changesRequestedCount })}</Badge>
            <Badge color={statusColor} variant="light">{t(`versions.reviews.status.${round.status}`)}</Badge>
          </Group>
        </Group>
      </Accordion.Control>
      <Accordion.Panel>
        <Stack gap="sm">
          {!round.isCurrentContent && <Alert color="orange" variant="light">{t("versions.reviews.outdated")}</Alert>}
          {round.closeReason && <Text size="sm" c="dimmed">{t(`versions.reviews.closeReason.${round.closeReason}`)}</Text>}
          {entries.isLoading && !entries.data ? <LoadingBlock py="xs" /> : entries.isError ? (
            <Alert color="red" variant="light">{loadErrorMessage(entries.error, t)}</Alert>
          ) : entries.data?.items.length ? (
            <Stack gap={0}>
              <Text size="xs" c="dimmed">{t("versions.reviews.newestFirst")}</Text>
              {entries.data.items.map((entry, index) => (
                <Stack key={entry.id} gap={2} py="sm" style={{ borderTop: index ? "1px solid var(--mantine-color-default-border)" : undefined }}>
                  <Group gap="xs">
                    <ReviewEntryBadge kind={entry.kind} />
                    <Text size="sm" fw={600}>{entry.author.name}{entry.author.deleted ? ` ${t("versions.reviews.deletedIdentity")}` : ""}</Text>
                    <Text size="xs" c="dimmed">{formatDateTime(entry.createdAt, i18n.language)}</Text>
                  </Group>
                  {entry.body && <Text size="sm" style={{ whiteSpace: "pre-wrap" }}>{entry.body}</Text>}
                </Stack>
              ))}
            </Stack>
          ) : <Text size="sm" c="dimmed">{t("versions.reviews.noEntries")}</Text>}
          {(entries.data?.total ?? 0) > ENTRY_PAGE_SIZE && (
            <Group justify="center">
              <Pagination value={page} onChange={setPage} total={Math.ceil((entries.data?.total ?? 0) / ENTRY_PAGE_SIZE)} size="xs"
                getControlProps={(control) => ({ "aria-label": t(`common.table.${control}Page`) })} />
            </Group>
          )}
          {mutation.isError && !(mutation.error instanceof ApiError && mutation.error.status === 409) && (
            <Alert color="red" variant="light" title={t("versions.reviews.actionFailed")}>
              {loadErrorMessage(mutation.error, t)}
            </Alert>
          )}
          {safeToAct && (round.canComment || round.canDecide) && (
            <Stack gap="xs">
              <Textarea
                label={t("versions.reviews.comment")}
                value={body}
                onChange={(event) => setBody(event.currentTarget.value)}
                maxLength={4000}
                autosize
                minRows={2}
                disabled={mutation.isPending}
              />
              <Group justify="flex-end">
                {round.canComment && (
                  <Button variant="default" leftSection={<IconMessage size={16} />} disabled={!commentReady || mutation.isPending} loading={mutation.isPending && mutation.variables === "COMMENT"} onClick={() => mutation.mutate("COMMENT")}>
                    {t("versions.reviews.addComment")}
                  </Button>
                )}
                {round.canDecide && (
                  <>
                    <Button color="orange" variant="light" leftSection={<IconX size={16} />} disabled={!commentReady || mutation.isPending} loading={mutation.isPending && mutation.variables === "CHANGES_REQUESTED"} onClick={() => mutation.mutate("CHANGES_REQUESTED")}>
                      {t("versions.reviews.requestChanges")}
                    </Button>
                    <Button leftSection={<IconCheck size={16} />} disabled={mutation.isPending} loading={mutation.isPending && mutation.variables === "APPROVED"} onClick={() => mutation.mutate("APPROVED")}>
                      {t("versions.reviews.approve")}
                    </Button>
                  </>
                )}
              </Group>
            </Stack>
          )}
        </Stack>
      </Accordion.Panel>
    </Accordion.Item>
  );
}

function ReviewEntryBadge({ kind }: { kind: VersionReviewEntryKind }) {
  const { t } = useTranslation();
  const color = kind === "APPROVED" ? "teal" : kind === "CHANGES_REQUESTED" ? "orange" : "gray";
  return <Badge size="xs" color={color} variant="light">{t(`versions.reviews.entry.${kind}`)}</Badge>;
}
