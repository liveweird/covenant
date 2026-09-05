import { Button, Code, Group, Modal, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { Finding } from "../api/versions";

/**
 * The Save-anyway confirmation (Toadie's): a strict save was rejected for SOFT findings —
 * schema or semantic errors the checks could not wave through. Lists them and offers the
 * explicit waiver — confirming retries the save with `allowInvalid=true`; the stored report
 * then rides the version until the text is fixed and rechecked.
 */
export default function SaveAnywayModal({
  findings,
  onCancel,
  onConfirm,
  saving,
}: {
  /** The strict rejection's soft errors; null keeps the modal closed. */
  findings: Finding[] | null;
  onCancel: () => void;
  onConfirm: () => void;
  saving: boolean;
}) {
  const { t } = useTranslation();
  const shown = (findings ?? []).slice(0, 10);
  const more = (findings?.length ?? 0) - shown.length;
  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened={findings !== null} onClose={onCancel} title={t("versions.saveAnyway.title")} centered size="lg">
      <Stack gap="sm">
        <Text size="sm">{t("versions.saveAnyway.intro", { count: findings?.length ?? 0 })}</Text>
        <Stack gap={4}>
          {shown.map((f, index) => (
            <Text size="sm" key={`${f.code}-${f.path ?? ""}-${index}`}>
              <Code>{f.code}</Code> — {f.message}
              {f.path ? <Text component="span" c="dimmed"> ({f.path})</Text> : null}
            </Text>
          ))}
          {more > 0 && (
            <Text size="sm" c="dimmed">
              {t("versions.saveAnyway.more", { count: more })}
            </Text>
          )}
        </Stack>
        <Group justify="flex-end" gap="sm">
          <Button variant="default" onClick={onCancel} disabled={saving}>
            {t("common.action.cancel")}
          </Button>
          <Button color="orange" onClick={onConfirm} loading={saving}>
            {t("versions.saveAnyway.confirm")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
