import { Code, Group, Text, Tooltip } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { VersionResponse } from "../api/versions";
import { formatDateTime, relativeTimeAgo } from "../utils/relativeTime";

/** The one-line facts under the viewer: format, spec version, content hash, check and update times. */
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
    </Group>
  );
}
