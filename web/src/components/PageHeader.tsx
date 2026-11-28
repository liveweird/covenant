import { type ReactNode } from "react";
import { Anchor, Group, Stack, Text, Title } from "@mantine/core";
import { IconArrowLeft } from "@tabler/icons-react";
import { Link as RouterLink } from "react-router-dom";
import classes from "../theme.module.css";

/**
 * Every page's first row (v1.19.0): the title (an `order={2}` heading — the name tests and
 * the e2e accessibility scan locate pages by), an optional dimmed description, an optional
 * back link above the title, the page's primary action(s) right-aligned on the SAME row,
 * and an optional toolbar row underneath (the list pages' filter/lens/kind row). One
 * component, so a "New …" button can only ever be in one place.
 */
export default function PageHeader({
  title,
  description,
  backTo,
  actions,
  toolbar,
}: {
  title: string;
  description?: ReactNode;
  backTo?: { to: string; label: string };
  actions?: ReactNode;
  toolbar?: ReactNode;
}) {
  return (
    <Stack component="header" gap="xs">
      <Group className={classes.pageHeader} gap="xs">
        <Stack gap={2} className={classes.pageHeading}>
          {backTo && (
            <Anchor component={RouterLink} to={backTo.to} size="xs" c="dimmed">
              <Group gap={4} wrap="nowrap" component="span">
                <IconArrowLeft size={14} aria-hidden />
                {backTo.label}
              </Group>
            </Anchor>
          )}
          <Title order={2} className={classes.pageTitle}>{title}</Title>
          {description && (
            <Text component="div" c="dimmed" size="sm" maw="88ch">
              {description}
            </Text>
          )}
        </Stack>
        {actions && (
          <Group gap="xs" wrap="wrap" className={classes.pageActions}>
            {actions}
          </Group>
        )}
      </Group>
      {toolbar}
    </Stack>
  );
}
