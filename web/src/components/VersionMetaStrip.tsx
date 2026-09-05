import { Anchor, Badge, Code, Group, Text, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { VersionResponse } from "../api/versions";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";
import { hasLocalChanges } from "../utils/syncComparison";

/** The one-line facts under the viewer: format, spec version, content hash, check and update times, the repo source and its sync state. */
export default function VersionMetaStrip({ version }: { version: VersionResponse }) {
  const { t, i18n } = useTranslation();
  return (
    <Group gap="md" wrap="wrap">
      <Text size="xs" c="dimmed">
        {t("versions.meta.format", { format: version.format })}
      </Text>
      {version.specVersion && (
        <Text size="xs" c="dimmed">
          {t("versions.meta.specVersion", { specVersion: version.specVersion })}
        </Text>
      )}
      <Tooltip label={version.contentSha256}>
        <Text size="xs" c="dimmed">
          {t("versions.meta.sha")} <Code fz="xs">{version.contentSha256.slice(0, 12)}</Code>
        </Text>
      </Tooltip>
      <Tooltip label={formatDateTime(version.checkedAt, i18n.language)}>
        <Text size="xs" c="dimmed">
          {t("versions.meta.checked", { when: relativeTimeAgo(version.checkedAt, i18n.language) })}
        </Text>
      </Tooltip>
      <Tooltip label={formatDateTime(version.updatedAt, i18n.language)}>
        <Text size="xs" c="dimmed">
          {t("versions.meta.updated", { when: relativeTimeAgo(version.updatedAt, i18n.language) })}
        </Text>
      </Tooltip>
      {version.sourceUrl != null && (
        <>
          <Text size="xs" c="dimmed">
            {t("versions.meta.source")}{" "}
            <Anchor href={version.sourceUrl} target="_blank" rel="noreferrer" size="xs" style={{ overflowWrap: "anywhere" }}>
              {version.sourceUrl}
            </Anchor>
          </Text>
          {version.lastSyncedAt > 0 && (
            <Tooltip label={formatDateTime(version.lastSyncedAt, i18n.language)}>
              <Text size="xs" c="dimmed">
                {t("versions.meta.synced", { when: relativeTimeAgo(version.lastSyncedAt, i18n.language) })}
              </Text>
            </Tooltip>
          )}
          {hasLocalChanges(version) && (
            <Badge variant="light" color="orange" size="xs">
              {t("versions.meta.localChanges")}
            </Badge>
          )}
        </>
      )}
    </Group>
  );
}
