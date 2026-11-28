import { Paper, Stack, Title } from "@mantine/core";
import type { ReactNode } from "react";

/**
 * One reader section with an anchored heading the TOC points at. Most document-level sections
 * use a card; operation groups stay flat because each operation already provides its own frame.
 */
export default function ReaderSection({
  id,
  title,
  children,
  pointer,
  framed = true,
}: {
  id: string;
  title: string;
  children: ReactNode;
  pointer?: string;
  framed?: boolean;
}) {
  const content = (
    <>
      <Title order={3} id={`${id}-title`} size="h4">
        {title}
      </Title>
      {children}
    </>
  );
  if (!framed) {
    return (
      <Stack gap="sm" component="section" id={id} aria-labelledby={`${id}-title`} data-pointer={pointer}>
        {content}
      </Stack>
    );
  }
  return (
    <Paper withBorder p="md" radius="md" component="section" id={id} aria-labelledby={`${id}-title`} data-pointer={pointer}>
      <Stack gap="sm">
        {content}
      </Stack>
    </Paper>
  );
}
