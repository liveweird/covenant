import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Modal, Stack, TextInput } from "@mantine/core";
import { useQueryClient } from "@tanstack/react-query";
import { setVersionSource, type VersionResponse } from "../api/versions";
import { isSourceUrl } from "../utils/document";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const SOURCE_ERROR_KEYS = {
  forbidden: "contracts.saveForbidden",
  notFound: "versions.gone",
  invalid: "versions.sourceLink.invalid",
  failedStatus: "common.error.saveFailedStatus",
  failed: "common.error.saveFailedNetwork",
} as const;

/**
 * Link (or unlink) a version's repo reference: a public https URL to the document in its
 * repository. Nothing is fetched here — Sync does that; the server mirrors the https rule.
 */
export default function SourceUrlModal({
  contractId,
  version,
  onClose,
}: {
  contractId: number;
  /** The version to link; null keeps the modal closed. */
  version: VersionResponse | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Modal opened={version !== null} onClose={onClose} title={t("versions.sourceLink.title", { version: version?.version ?? "" })} centered>
      {version !== null && <SourceUrlForm contractId={contractId} version={version} onClose={onClose} />}
    </Modal>
  );
}

function SourceUrlForm({ contractId, version, onClose }: { contractId: number; version: VersionResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [url, setUrl] = useState(version.sourceUrl ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const trimmed = url.trim();
  const invalid = trimmed !== "" && !isSourceUrl(trimmed);

  async function onSave() {
    if (invalid) return;
    setSaving(true);
    setError(null);
    try {
      await setVersionSource(contractId, version.id, trimmed === "" ? null : trimmed);
      showSuccessToast(t("versions.toast.sourceSaved"));
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      onClose();
    } catch (err) {
      setError(saveErrorMessage(err, t, SOURCE_ERROR_KEYS));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Stack gap="sm">
      <TextInput
        label={t("versions.sourceLink.field")}
        description={t("versions.sourceLink.hint")}
        placeholder="https://github.com/org/repo/blob/main/contracts/orders.yaml"
        value={url}
        onChange={(e) => setUrl(e.currentTarget.value)}
        error={invalid ? t("versions.sourceLink.invalid") : undefined}
        autoFocus
      />
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={saving}>
          {t("common.action.cancel")}
        </Button>
        <Button onClick={() => void onSave()} loading={saving} disabled={invalid}>
          {t("versions.sourceLink.save")}
        </Button>
      </Group>
    </Stack>
  );
}
