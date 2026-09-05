import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Group, Pagination, Stack, Text, Timeline } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { listContractEvents, type ContractEvent } from "../api/contracts";
import LoadingBlock from "./LoadingBlock";
import { CONTRACT_TYPE_LABEL } from "../utils/contractForm";
import { LIFECYCLES } from "../utils/lifecycle";
import { formatDateTime } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

const PAGE_SIZE = 10;

/** The lifecycle label the pills use; a value this build does not know renders raw (forward-compat). */
function lifecycleLabel(value: string | undefined, t: TFunction): string {
  const known = LIFECYCLES.find((lifecycle) => lifecycle === value);
  return known ? t(`versions.lifecycle.${known}`) : (value ?? "");
}

/** `TEAM:<id>` / `USER:<id>` — the owner params are ids, not names (a renamed team keeps the trail honest). */
function ownerRef(param: string | undefined, t: TFunction): string {
  const [kind, id] = (param ?? "").split(":");
  if (kind === "TEAM") return t("contracts.history.ownerTeam", { id });
  if (kind === "USER") return t("contracts.history.ownerUser", { id });
  return param ?? "";
}

/** The one localized sentence a timeline entry leads with. */
function describeEvent(event: ContractEvent, t: TFunction): string {
  const { version } = event.params;
  switch (event.type) {
    case "CREATED": {
      const type = event.params.type;
      return t("contracts.history.event.created", { type: type && type in CONTRACT_TYPE_LABEL ? CONTRACT_TYPE_LABEL[type as keyof typeof CONTRACT_TYPE_LABEL] : type });
    }
    case "UPDATED":
      return t("contracts.history.event.updated");
    case "OWNER_CHANGED":
      return t("contracts.history.event.ownerChanged");
    case "DELETED":
      return t("contracts.history.event.deleted");
    case "VERSION_CREATED":
      return t("contracts.history.event.versionCreated", { version });
    case "VERSION_CONTENT_UPDATED":
      return t("contracts.history.event.versionContentUpdated", { version });
    case "VERSION_TRANSITIONED":
      return t("contracts.history.event.versionTransitioned", { version, from: lifecycleLabel(event.params.from, t), to: lifecycleLabel(event.params.to, t) });
    case "VERSION_RECHECKED":
      return t("contracts.history.event.versionRechecked", { version });
    case "VERSION_DELETED":
      return t("contracts.history.event.versionDeleted", { version });
    case "VERSION_SOURCE_CHANGED":
      return event.params.sourceUrl ? t("contracts.history.event.versionSourceSet", { version }) : t("contracts.history.event.versionSourceCleared", { version });
    case "VERSION_SYNCED":
      return t("contracts.history.event.versionSynced", { version });
    case "IMPORTED":
      return t("contracts.history.event.imported", { version });
    default:
      // Forward-compat: an event kind this client build doesn't know yet — show the raw type.
      return event.type;
  }
}

/** One body line for the events whose params carry a value worth repeating. */
function detailLine(event: ContractEvent, t: TFunction): string | null {
  switch (event.type) {
    case "UPDATED":
      return event.params.name ? t("contracts.history.detail.name", { name: event.params.name }) : null;
    case "OWNER_CHANGED":
      return t("contracts.history.detail.owner", { from: ownerRef(event.params["owner.from"], t), to: ownerRef(event.params["owner.to"], t) });
    case "VERSION_SOURCE_CHANGED":
      return event.params.sourceUrl ? t("contracts.history.detail.source", { url: event.params.sourceUrl }) : null;
    default:
      return null;
  }
}

/**
 * A contract's change history (Toadie's `CatalogFileHistory`, ported): the server's structural
 * events rendered in the viewer's language, newest first (server-ordered — never re-sorted
 * here). A failed load must never masquerade as an empty history.
 */
export default function ContractHistory({ contractId }: { contractId: number }) {
  const { t, i18n } = useTranslation();
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, error } = useQuery({
    // Inside the ["contracts"] prefix, so every contract mutation refreshes the trail.
    queryKey: ["contracts", "events", contractId, page],
    queryFn: () => listContractEvents(contractId, page, PAGE_SIZE),
  });

  let body;
  if (isLoading) {
    body = <LoadingBlock py="sm" />;
  } else if (isError) {
    body = (
      <Alert color="red" variant="light">
        {loadErrorMessage(error, t)}
      </Alert>
    );
  } else if (!data || data.items.length === 0) {
    body = (
      <Text c="dimmed" size="sm">
        {t("contracts.history.empty")}
      </Text>
    );
  } else {
    body = (
      <>
        <Timeline bulletSize={12} lineWidth={2}>
          {data.items.map((event) => {
            const detail = detailLine(event, t);
            return (
              <Timeline.Item key={event.id} title={describeEvent(event, t)}>
                {detail && (
                  <Text size="sm" c="dimmed">
                    {detail}
                  </Text>
                )}
                <Text size="xs" c="dimmed">
                  {event.userName} · {formatDateTime(event.timestamp, i18n.language)}
                </Text>
              </Timeline.Item>
            );
          })}
        </Timeline>
        {data.total > PAGE_SIZE && (
          <Group justify="center">
            <Pagination size="sm" value={page} onChange={setPage} total={Math.ceil(data.total / PAGE_SIZE)} />
          </Group>
        )}
      </>
    );
  }

  return (
    <Stack gap="sm" role="region" aria-label={t("contracts.history.title")}>
      <Text fw={600} size="sm">
        {t("contracts.history.title")}
      </Text>
      {body}
    </Stack>
  );
}
