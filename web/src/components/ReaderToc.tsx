import { Anchor, Box, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TocEntry } from "../utils/readerToc";
import classes from "../theme.module.css";

/** The reader's table of contents, inside the version page's side panel — plain in-page anchors, so the browser owns the scroll; the panel itself is the sticky element. */
export default function ReaderToc({ entries }: { entries: readonly TocEntry[] }) {
  const { t } = useTranslation();
  return (
    <Box component="nav" aria-label={t("reader.tocAria")} className={classes.asideToc}>
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
