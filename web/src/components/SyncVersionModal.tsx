import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { Alert, Anchor, Badge, Button, Group, Loader, Modal, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import type { ContractResponse } from "../api/contracts";
import { fetchContractUrl } from "../api/contracts";
import { checkDocument, getSyncState, isHardFinding, syncVersion, type VersionResponse } from "../api/versions";
import TextDiffView from "./TextDiffView";
import { newVersionPath } from "../utils/contractLinks";
import { normalizeSourceUrl } from "../utils/document";
import { isContentEditable } from "../utils/lifecycle";
import { collapseUnchanged } from "../utils/lineDiff";
import { relativeTimeAgo } from "../utils/relativeTime";
import { FETCH_URL_ERROR_KEYS, loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { compareSyncSides } from "../utils/syncComparison";
import { showSuccessToast } from "../utils/toast";

/** What the New-version page reads from `location.state` when the sync modal seeds it with the repo copy. */
export type SeededDocument = { content: string; sourceUrl: string | null };

const SYNC_ERROR_KEYS = {
  forbidden: "contracts.saveForbidden",
  notFound: "versions.gone",
  conflict: "versions.contentLocked",
  invalid: "versions.sync.invalid",
  failedStatus: "common.error.saveFailedStatus",
  failed: "common.error.saveFailedNetwork",
} as const;

/**
 * The Sync-from-source modal (Toadie's SyncCatalogFileModal, ported to the raw text): fetches the
 * repo copy through the SSRF-guarded server fetch, shows WHICH side changed since the last sync
 * (the stored baseline attributes it) plus the stored → repo line diff, and — for an editable
 * version — overwrites the stored document on explicit confirmation; a published version's text
 * is locked, so the modal offers a NEW version seeded with the repo copy instead. Covenant → repo
 * sync deliberately does not exist. Shell + body split so the body's queries narrow on a real
 * version and the one Modal keeps its open/close transition.
 */
export default function SyncVersionModal({
  contract,
  version,
  onClose,
  onSynced,
}: {
  contract: ContractResponse;
  /** The version to sync; null keeps the modal closed. */
  version: VersionResponse | null;
  onClose: () => void;
  /** Fired only after a SUCCESSFUL sync. */
  onSynced: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const [syncing, setSyncing] = useState(false);
  return (
    <Modal
      opened={version !== null}
      // The busy guard covers Esc/overlay/the X too — a dismissal mid-POST would unmount the error.
      onClose={() => {
        if (!syncing) onClose();
      }}
      title={t("versions.sync.title", { version: version?.version ?? "" })}
      size="xl"
      centered
    >
      {version !== null && version.sourceUrl != null && (
        <SyncModalBody contract={contract} version={version} sourceUrl={version.sourceUrl} syncing={syncing} onSyncingChange={setSyncing} onClose={onClose} onSynced={onSynced} />
      )}
    </Modal>
  );
}

function SyncModalBody({
  contract,
  version,
  sourceUrl,
  syncing,
  onSyncingChange,
  onClose,
  onSynced,
}: {
  contract: ContractResponse;
  version: VersionResponse;
  sourceUrl: string;
  syncing: boolean;
  onSyncingChange: (syncing: boolean) => void;
  onClose: () => void;
  onSynced: () => Promise<void> | void;
}) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [syncError, setSyncError] = useState<string | null>(null);
  const editable = isContentEditable(version.lifecycle);

  const syncState = useQuery({
    queryKey: ["contracts", "syncState", contract.id, version.id],
    queryFn: () => getSyncState(contract.id, version.id),
  });
  // Keyed OUTSIDE the ["contracts"] prefix on purpose: the post-sync invalidation must never
  // re-trigger the server-side outbound fetch (or the check of the fetched copy).
  const repoFetch = useQuery({
    queryKey: ["repoCopy", version.id],
    queryFn: () => fetchContractUrl(normalizeSourceUrl(sourceUrl)),
    staleTime: 0,
    gcTime: 0,
  });
  const repo = repoFetch.data ?? null;
  const repoCheck = useQuery({
    queryKey: ["repoFindings", version.id],
    queryFn: () => {
      // `enabled` gates but does not narrow — guard honestly instead of casting.
      if (repo == null) throw new Error("repo copy checked before it was fetched");
      return checkDocument({ type: contract.type, content: repo, version: version.version, contractId: contract.id });
    },
    enabled: repo != null,
    staleTime: 0,
    gcTime: 0,
  });

  const lastSyncedAt = syncState.data?.lastSyncedAt ?? version.lastSyncedAt;
  const { inSync, localChanged, repoChanged, diff } = compareSyncSides({
    current: version.content,
    repo,
    baseline: syncState.data?.syncedContent ?? null,
    updatedAt: version.updatedAt,
    lastSyncedAt,
  });
  const repoFindings = repoCheck.data?.findings ?? [];
  const repoBroken = repoFindings.some(isHardFinding);
  const loading = syncState.isLoading || repoFetch.isLoading || (repo != null && repoCheck.isLoading);
  const loadError = syncState.isError
    ? loadErrorMessage(syncState.error, t)
    : repoFetch.isError
      ? saveErrorMessage(repoFetch.error, t, FETCH_URL_ERROR_KEYS)
      : null;
  const ready = !loading && loadError == null && repo != null;

  async function onConfirm() {
    if (repo == null) return;
    onSyncingChange(true);
    setSyncError(null);
    try {
      await syncVersion(contract.id, version.id, repo);
      showSuccessToast(t("versions.toast.synced"));
      onSyncingChange(false);
      onClose();
      // Refresh AFTER closing — the modal's own repo queries sit outside this prefix, so no outbound re-fetch fires.
      void queryClient.invalidateQueries({ queryKey: ["contracts"] });
      await onSynced();
    } catch (err) {
      onSyncingChange(false);
      setSyncError(saveErrorMessage(err, t, SYNC_ERROR_KEYS));
    }
  }

  function onNewVersion() {
    if (repo == null) return;
    const seeded: SeededDocument = { content: repo, sourceUrl };
    onClose();
    navigate(newVersionPath(contract.id, version.id), { state: seeded });
  }

  return (
    <Stack gap="sm">
      <Text size="sm">
        {t("versions.sync.sourceLabel")}{" "}
        <Anchor href={sourceUrl} target="_blank" rel="noreferrer" size="sm" style={{ overflowWrap: "anywhere" }}>
          {sourceUrl}
        </Anchor>
      </Text>
      <Text size="sm" c="dimmed">
        {lastSyncedAt > 0 ? t("versions.sync.lastSynced", { ago: relativeTimeAgo(lastSyncedAt, i18n.language) }) : t("versions.sync.neverSynced")}
      </Text>
      {loading && <Loader size="sm" aria-label={t("versions.sync.loadingAria")} />}
      {loadError != null && (
        <Alert color="red" variant="light" title={t("versions.sync.loadFailed")}>
          {loadError}
        </Alert>
      )}
      {ready && (
        <>
          <Group gap="xs">
            {inSync ? (
              <Badge variant="light" color="teal" size="sm">
                {t("versions.sync.inSync")}
              </Badge>
            ) : (
              <>
                {/* No baseline (never synced) = the sides cannot be attributed; the diff says it all. */}
                {repoChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("versions.sync.changedInRepo")}
                  </Badge>
                )}
                {localChanged && (
                  <Badge variant="light" color="orange" size="sm">
                    {t("versions.sync.changedLocally")}
                  </Badge>
                )}
              </>
            )}
          </Group>
          {diff != null && <TextDiffView rows={collapseUnchanged(diff)} label={t("versions.sync.diffLabel")} />}
          {repoBroken ? (
            <Alert color="red" variant="light">
              {t("versions.sync.repoBroken")}
            </Alert>
          ) : (
            repoFindings.length > 0 && (
              <Alert color="orange" variant="light">
                {t("versions.sync.findingsWarning", { count: repoFindings.length })}
              </Alert>
            )
          )}
          {!inSync && (
            <Text size="sm" c="dimmed">
              {editable ? t("versions.sync.overwriteWarning") : t("versions.sync.lockedHint")}
            </Text>
          )}
        </>
      )}
      {syncError != null && (
        <Alert color="red" variant="light" title={t("versions.sync.failed")}>
          {syncError}
        </Alert>
      )}
      <Group justify="flex-end" mt="sm">
        <Button variant="default" onClick={onClose} disabled={syncing}>
          {t("common.action.cancel")}
        </Button>
        {editable ? (
          <Button onClick={() => void onConfirm()} loading={syncing} disabled={!ready || inSync || repoBroken}>
            {t("versions.sync.confirm")}
          </Button>
        ) : (
          <Button onClick={onNewVersion} disabled={!ready || repoBroken}>
            {t("versions.sync.newVersion")}
          </Button>
        )}
      </Group>
    </Stack>
  );
}
