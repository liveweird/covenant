import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Alert, Anchor, Badge, Button, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconExternalLink, IconUnlink } from "@tabler/icons-react";
import type { ToadieRegistrySource } from "../api/toadie";
import ConfirmActionModal from "./ConfirmActionModal";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

export default function ToadieRegistrySourceStatus({ source, onDetach, compact = false }: {
  source: ToadieRegistrySource | null | undefined;
  onDetach?: () => Promise<void>;
  compact?: boolean;
}) {
  const { t, i18n } = useTranslation();
  const [confirming, setConfirming] = useState(false);
  const [detaching, setDetaching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!source) return <Text size="sm" c="dimmed">—</Text>;

  const displays = sourceStatuses(source, t);
  async function detach() {
    if (!onDetach) return;
    setDetaching(true);
    setError(null);
    try {
      await onDetach();
      showSuccessToast(t("toadie.toast.sourceDetached"));
      setConfirming(false);
    } catch (caught) {
      setError(saveErrorMessage(caught, t, { forbidden: "toadie.error.adminOnly", notFound: "toadie.error.gone", failedStatus: "common.error.actionFailedStatus", failed: "toadie.registry.detachFailed" }));
      setConfirming(false);
    } finally {
      setDetaching(false);
    }
  }

  return <Stack gap={4}>
    <Group gap="xs" wrap="wrap">
      {source.url ? <Anchor href={source.url} target="_blank" rel="noreferrer" size="sm" aria-label={t("toadie.usage.openEntity", { name: source.title })}>{source.title}<IconExternalLink size={12} style={{ marginInlineStart: 4 }} /></Anchor> : <Text size="sm">{source.title}</Text>}
      {displays.map((display) => <Badge key={display.label} color={display.color} variant="light" size="sm">{display.label}</Badge>)}
      {onDetach && !compact && <Button size="compact-xs" variant="subtle" color="gray" leftSection={<IconUnlink size={13} />} onClick={() => setConfirming(true)}>{t("toadie.registry.detach")}</Button>}
    </Group>
    {!compact && <Text size="xs" c="dimmed">{source.connectionName} · {source.identifier}</Text>}
    {!compact && source.lastSyncedAt != null && <Tooltip label={formatDateTime(source.lastSyncedAt, i18n.language)}><Text size="xs" c="dimmed">{t("toadie.registry.sourceLastSynced", { time: relativeTimeAgo(source.lastSyncedAt, i18n.language) })}</Text></Tooltip>}
    {!compact && source.lastErrorCode && <Text size="xs" c="orange">{t(`toadie.registry.issue.${source.lastErrorCode}`, { defaultValue: t("toadie.registry.sourceError", { code: source.lastErrorCode }) })}</Text>}
    {!compact && source.cache.lastErrorCode && <Text size="xs" c="orange">{t("toadie.registry.sourceRefreshError", { code: source.cache.lastErrorCode })}</Text>}
    {error && <Alert color="red" variant="light">{error}</Alert>}
    <ConfirmActionModal opened={confirming} onClose={() => setConfirming(false)} title={t("toadie.registry.detachTitle")} message={t("toadie.registry.detachBody")} cancelLabel={t("common.action.cancel")} confirmLabel={t("toadie.registry.detach")} onConfirm={() => void detach()} loading={detaching} />
  </Stack>;
}

function sourceStatuses(source: ToadieRegistrySource, t: TFunction): Array<{ label: string; color: string }> {
  const availability = source.status === "MISSING"
    ? { label: t("toadie.registry.sourceMissing"), color: "orange" }
    : source.status === "CONFLICT"
      ? { label: t("toadie.registry.sourceConflict"), color: "orange" }
      : source.status === "DISCONNECTED"
        ? { label: t("toadie.registry.sourceDisconnected"), color: "gray" }
        : null;
  const freshness = source.cache.state === "DISCONNECTED"
    ? { label: t("toadie.registry.sourceDisconnected"), color: "gray" }
    : source.cache.state === "DISABLED"
      ? { label: t("toadie.registry.sourceDisabled"), color: "gray" }
      : source.cache.refreshing
        ? { label: t("toadie.status.refreshing"), color: "blue" }
        : source.cache.state === "CURRENT"
        ? { label: t("toadie.registry.sourceCurrent"), color: "teal" }
        : source.cache.state === "STALE"
          ? { label: t("toadie.registry.sourceStale"), color: "orange" }
          : { label: t("toadie.registry.sourceAwaiting"), color: "gray" };
  if (!availability) return [freshness];
  if (source.cache.state === "CURRENT" || availability.label === freshness.label) return [availability];
  return [availability, freshness];
}
