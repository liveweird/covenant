import { Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DiffRow } from "../utils/lineDiff";

// Scheme-aware via Mantine's light-variant tokens — no hand-picked dark-mode rgba.
const DIFF_COLORS = {
  removed: { background: "var(--mantine-color-red-light)", color: "var(--mantine-color-red-light-color)", prefix: "-" },
  added: { background: "var(--mantine-color-teal-light)", color: "var(--mantine-color-teal-light-color)", prefix: "+" },
  same: { background: "transparent", color: "inherit", prefix: " " },
} as const;

/**
 * The version comparison's line-diff pane (Toadie's YamlDiffView): monospace −/+ rows in a
 * bordered scroll region. The add/remove signal is never colour-only — the prefix travels in
 * the same text node, so assistive tech announces it. Focusable (`tabIndex`) so a keyboard
 * user can scroll a long diff, and named for AT via role="group".
 */
export default function TextDiffView({ rows, label }: { rows: readonly DiffRow[]; label: string }) {
  const { t } = useTranslation();
  return (
    <Stack
      gap={0}
      role="group"
      aria-label={label}
      tabIndex={0}
      style={{
        fontFamily: "var(--mantine-font-family-monospace)",
        fontSize: "var(--mantine-font-size-xs)",
        maxHeight: "70vh",
        overflow: "auto",
        border: "1px solid light-dark(var(--mantine-color-gray-3), var(--mantine-color-dark-4))",
        borderRadius: "var(--mantine-radius-md)",
        backgroundColor: "var(--mantine-color-body)",
        padding: 8,
      }}
    >
      {rows.map((row, index) =>
        row.kind === "skipped" ? (
          <Text key={index} component="div" size="xs" c="dimmed" ta="center" py={4} style={{ borderBlock: "1px dashed var(--mantine-color-default-border)" }}>
            {t("versions.diff.skipped", { count: row.count })}
          </Text>
        ) : (
          <Text
            key={index}
            component="pre"
            size="xs"
            m={0}
            px={4}
            style={{ whiteSpace: "pre-wrap", backgroundColor: DIFF_COLORS[row.kind].background, color: DIFF_COLORS[row.kind].color, fontFamily: "inherit" }}
          >
            {`${DIFF_COLORS[row.kind].prefix} ${row.text}`}
          </Text>
        ),
      )}
    </Stack>
  );
}
