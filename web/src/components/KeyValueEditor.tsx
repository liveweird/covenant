import { ActionIcon, Button, Group, Stack, Text, TextInput } from "@mantine/core";
import { IconPlus, IconX } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import type { KeyValueRow } from "../utils/tryIt";

/** Rows of name/value inputs (query parameters, headers) — blank names are dropped at send time. */
export default function KeyValueEditor({
  label,
  rows,
  onChange,
  hint,
}: {
  label: string;
  rows: readonly KeyValueRow[];
  onChange: (rows: KeyValueRow[]) => void;
  hint?: string;
}) {
  const { t } = useTranslation();
  const update = (index: number, patch: Partial<KeyValueRow>) => onChange(rows.map((r, i) => (i === index ? { ...r, ...patch } : r)));
  return (
    <Stack gap={4} role="group" aria-label={label}>
      <Group justify="space-between">
        <Text size="sm" fw={500}>
          {label}
        </Text>
        <Button size="compact-xs" variant="subtle" color="gray" leftSection={<IconPlus size={12} />} onClick={() => onChange([...rows, { key: "", value: "" }])}>
          {t("tryIt.keyValue.add")}
        </Button>
      </Group>
      {hint && (
        <Text size="xs" c="dimmed">
          {hint}
        </Text>
      )}
      {rows.length === 0 && (
        <Text size="xs" c="dimmed">
          {t("tryIt.keyValue.empty")}
        </Text>
      )}
      {rows.map((row, i) => (
        <Group key={i} gap="xs" wrap="nowrap" align="flex-start">
          <TextInput
            size="xs"
            style={{ flex: 1 }}
            aria-label={`${label}: ${t("tryIt.keyValue.key")} ${i + 1}`}
            placeholder={t("tryIt.keyValue.key")}
            value={row.key}
            onChange={(e) => update(i, { key: e.currentTarget.value })}
          />
          <TextInput
            size="xs"
            style={{ flex: 2 }}
            aria-label={`${label}: ${t("tryIt.keyValue.value")} ${i + 1}`}
            placeholder={t("tryIt.keyValue.value")}
            value={row.value}
            onChange={(e) => update(i, { value: e.currentTarget.value })}
          />
          <ActionIcon size="sm" aria-label={t("tryIt.keyValue.remove", { name: row.key || String(i + 1) })} onClick={() => onChange(rows.filter((_, j) => j !== i))}>
            <IconX size={14} />
          </ActionIcon>
        </Group>
      ))}
    </Stack>
  );
}
