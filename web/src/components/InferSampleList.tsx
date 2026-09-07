import type { TFunction } from "i18next";
import { useTranslation } from "react-i18next";
import { ActionIcon, Badge, Card, Group, Stack, Text } from "@mantine/core";
import { IconX } from "@tabler/icons-react";
import type { ContractType } from "../api/contracts";
import type { HttpExchangeSample, MessageBatchSample, RelationSample } from "../api/infer";
import MethodBadge from "./MethodBadge";

/** One sample of what the draft is inferred from — the shape read depends on the contract type. */
export type InferSample = HttpExchangeSample | MessageBatchSample | RelationSample;

function sampleName(type: ContractType, sample: InferSample): string {
  if (type === "OPENAPI") return (sample as HttpExchangeSample).url;
  if (type === "ASYNCAPI") return (sample as MessageBatchSample).channel;
  return (sample as RelationSample).name;
}

function sampleMeta(type: ContractType, sample: InferSample, t: TFunction): string {
  if (type === "OPENAPI") return String((sample as HttpExchangeSample).status);
  if (type === "ASYNCAPI") return t("infer.samples.payloadCount", { count: (sample as MessageBatchSample).payloads?.length ?? 0 });
  const relation = sample as RelationSample;
  // A pasted ODCS relation sample carries `rows`, not `columns` (columns are only ever
  // described from a live observe leg) — show a row count instead of "0 columns".
  if ((relation.columns?.length ?? 0) === 0 && (relation.rows?.length ?? 0) > 0) {
    return t("infer.samples.rowCount", { count: relation.rows?.length ?? 0 });
  }
  return t("infer.samples.columnCount", { count: relation.columns?.length ?? 0 });
}

/** A card per sample (method + path + status / topic + payload count / relation + column count), removable. */
export default function InferSampleList({
  type,
  samples,
  onRemove,
}: {
  type: ContractType;
  samples: readonly InferSample[];
  onRemove: (index: number) => void;
}) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs">
      <Text size="sm" c="dimmed">
        {samples.length === 0 ? t("infer.samples.none") : t("infer.samples.count", { count: samples.length })}
      </Text>
      {samples.map((sample, index) => {
        const name = sampleName(type, sample);
        return (
          <Card key={index} withBorder padding="xs" radius="md">
            <Group justify="space-between" wrap="nowrap" gap="xs">
              <Group gap="xs" wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
                {type === "OPENAPI" && <MethodBadge method={(sample as HttpExchangeSample).method} size="xs" />}
                <Text size="sm" ff="monospace" truncate>
                  {name}
                </Text>
                <Badge variant="light" color="gray" size="sm">
                  {sampleMeta(type, sample, t)}
                </Badge>
              </Group>
              <ActionIcon size="sm" aria-label={t("infer.removeAria", { name })} onClick={() => onRemove(index)}>
                <IconX size={14} />
              </ActionIcon>
            </Group>
          </Card>
        );
      })}
    </Stack>
  );
}
