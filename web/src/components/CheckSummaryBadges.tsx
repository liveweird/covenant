import { Badge, Group, Tooltip } from "@mantine/core";
import { IconAlertTriangle } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";

/** The compact error/warning counts on a version row (red = errors, orange = warnings); a sat-out check is flagged. */
export default function CheckSummaryBadges({ errors, warnings, complete }: { errors: number; warnings: number; complete: boolean }) {
  const { t } = useTranslation();
  return (
    <Group gap={4} wrap="nowrap">
      {errors > 0 && (
        <Badge color="red" size="xs">
          {t("findings.count.ERROR", { count: errors })}
        </Badge>
      )}
      {warnings > 0 && (
        <Badge color="orange" size="xs">
          {t("findings.count.WARN", { count: warnings })}
        </Badge>
      )}
      {errors === 0 && warnings === 0 && (
        <Badge color="teal" size="xs" variant="outline">
          {t("findings.cleanShort")}
        </Badge>
      )}
      {!complete && (
        <Tooltip label={t("findings.incompleteTitle")}>
          <IconAlertTriangle size={14} color="var(--covenant-ink-warning)" aria-label={t("findings.incompleteTitle")} />
        </Tooltip>
      )}
    </Group>
  );
}
