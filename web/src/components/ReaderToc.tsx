import { Anchor, Box, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import classes from "../theme.module.css";

export type TocEntry = { id: string; label: string; children?: TocEntry[] };

/** The reader's sticky table of contents — plain in-page anchors, so the browser owns the scroll. */
export default function ReaderToc({ entries }: { entries: readonly TocEntry[] }) {
  const { t } = useTranslation();
  return (
    <Box component="nav" aria-label={t("reader.tocAria")} className={classes.readerToc}>
      <Stack gap={2}>
        {entries.map((e) => (
          <Box key={e.id}>
            <Anchor href={`#${e.id}`} size="sm" fw={500} className={classes.anchor}>
              {e.label}
            </Anchor>
            {e.children && e.children.length > 0 && (
              <Stack gap={0} pl="sm">
                {e.children.map((c) => (
                  <Anchor key={c.id} href={`#${c.id}`} size="xs" c="dimmed" className={classes.anchor} truncate="end">
                    {c.label}
                  </Anchor>
                ))}
              </Stack>
            )}
          </Box>
        ))}
        {entries.length === 0 && (
          <Text size="xs" c="dimmed">
            {t("reader.tocEmpty")}
          </Text>
        )}
      </Stack>
    </Box>
  );
}
