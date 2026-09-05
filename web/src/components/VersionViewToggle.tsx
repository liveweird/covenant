import { Group, SegmentedControl } from "@mantine/core";
import { IconBook2, IconCode } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { VersionView } from "../hooks/useVersionView";

/** Reader / Source — the version page's two renderings of one stored document. */
export default function VersionViewToggle({ view, onChange, disabled }: { view: VersionView; onChange: (v: VersionView) => void; disabled?: boolean }) {
  const { t } = useTranslation();
  return (
    <SegmentedControl
      size="xs"
      value={view}
      onChange={(v) => onChange(v as VersionView)}
      disabled={disabled}
      aria-label={t("reader.viewAria")}
      data={[
        {
          value: "reader",
          label: (
            <Group gap={6} wrap="nowrap">
              <IconBook2 size={14} />
              <span>{t("reader.view.reader")}</span>
            </Group>
          ),
        },
        {
          value: "source",
          label: (
            <Group gap={6} wrap="nowrap">
              <IconCode size={14} />
              <span>{t("reader.view.source")}</span>
            </Group>
          ),
        },
      ]}
    />
  );
}
