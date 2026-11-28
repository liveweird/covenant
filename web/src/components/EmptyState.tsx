import { Center, Stack, Text, ThemeIcon } from "@mantine/core";
import type { Icon } from "@tabler/icons-react";

/**
 * The shared zero-rows body for list tables (icon + dimmed one-liner). Takes the Tabler icon
 * COMPONENT and sizes/strokes it itself, so every empty state draws the same glyph weight.
 */
export default function EmptyState({ icon: Icon, label }: { icon: Icon; label: string }) {
  return (
    <Center py="xl" data-empty-state>
      <Stack align="center" gap="xs">
        <ThemeIcon variant="light" color="gray" size={44} radius="md" aria-hidden>
          <Icon size={24} stroke={1.5} />
        </ThemeIcon>
        <Text c="dimmed" size="sm" ta="center" maw="54ch">
          {label}
        </Text>
      </Stack>
    </Center>
  );
}
