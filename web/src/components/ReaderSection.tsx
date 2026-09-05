import { Paper, Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

/** One reader section: a card with an anchored heading the TOC points at. */
export default function ReaderSection({ id, title, children, pointer }: { id: string; title: string; children: ReactNode; pointer?: string }) {
  return (
    <Paper withBorder p="md" radius="md" component="section" id={id} aria-labelledby={`${id}-title`} data-pointer={pointer}>
      <Stack gap="sm">
        <Title order={3} id={`${id}-title`} size="h4">
          {title}
        </Title>
        {children}
      </Stack>
    </Paper>
  );
}
