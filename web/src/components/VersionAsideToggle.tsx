import { ActionIcon, Indicator, Tooltip } from "@mantine/core";
import { IconLayoutSidebarRightCollapse, IconLayoutSidebarRightExpand } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";

/**
 * The version page's header toggle for its single side panel (Contents above Findings): an
 * Indicator carries the panel's ERROR count so it stays visible even while the panel is hidden.
 */
export default function VersionAsideToggle({ open, errorCount, onToggle }: { open: boolean; errorCount: number; onToggle: () => void }) {
  const { t } = useTranslation();
  return (
    <Indicator color="red" size={16} label={errorCount} disabled={open || errorCount === 0}>
      <Tooltip label={t("versions.toggleAside")}>
        <ActionIcon variant="default" aria-label={t("versions.toggleAside")} aria-pressed={open} onClick={onToggle}>
          {open ? <IconLayoutSidebarRightCollapse size={18} /> : <IconLayoutSidebarRightExpand size={18} />}
        </ActionIcon>
      </Tooltip>
    </Indicator>
  );
}
