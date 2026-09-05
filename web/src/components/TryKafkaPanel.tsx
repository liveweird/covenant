import { Alert, Badge, Button, Code, Group, NumberInput, Paper, Select, Stack, Text, TextInput } from "@mantine/core";
import { useMutation } from "@tanstack/react-query";
import { IconDownload, IconSend } from "@tabler/icons-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { tryKafkaPublish, tryKafkaRead, type KafkaChannelSummary, type KafkaRecordView, type TryKafkaPublishResult, type TryKafkaReadResult } from "../api/tryIt";
import { rowsToRecord, tryErrorMessage, type KeyValueRow } from "../utils/tryIt";
import FindingsPanel from "./FindingsPanel";
import KeyValueEditor from "./KeyValueEditor";
import LazyCodeEditor from "./LazyCodeEditor";

function RecordCard({ record }: { record: KafkaRecordView }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="xs">
      <Group gap="xs" mb={4}>
        <Text size="xs" fw={500}>
          {t("tryIt.kafka.record", { partition: record.partition, offset: record.offset })}
        </Text>
        <Text size="xs" c="dimmed">
          {new Date(record.timestamp).toISOString()}
        </Text>
        {record.key != null && <Code fz="xs">{record.key}</Code>}
        {record.encoding === "base64" && <Badge color="gray">{t("tryIt.kafka.base64")}</Badge>}
        {record.truncated && <Badge color="orange">{t("tryIt.kafka.truncated")}</Badge>}
      </Group>
      {record.payload == null ? (
        <Text size="xs" c="dimmed">
          {t("tryIt.kafka.noPayload")}
        </Text>
      ) : (
        <Code block fz="xs" style={{ whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
          {record.payload}
        </Code>
      )}
    </Paper>
  );
}

/** The AsyncAPI leg: publish (writers only) or read the newest records of a channel's topic. */
export default function TryKafkaPanel({
  contractId,
  versionId,
  environmentId,
  channels,
  canWrite,
}: {
  contractId: number;
  versionId: number;
  environmentId: number;
  channels: readonly KafkaChannelSummary[];
  canWrite: boolean;
}) {
  const { t } = useTranslation();
  const [channelKey, setChannelKey] = useState<string | null>(channels.length === 1 ? channels[0].channel : null);
  const [message, setMessage] = useState<string | null>(null);
  const [key, setKey] = useState("");
  const [headers, setHeaders] = useState<KeyValueRow[]>([]);
  const [payload, setPayload] = useState("");
  const [limit, setLimit] = useState<string | number>(10);
  const [published, setPublished] = useState<TryKafkaPublishResult | null>(null);
  const [read, setRead] = useState<TryKafkaReadResult | null>(null);
  const channel = channels.find((c) => c.channel === channelKey) ?? null;
  const messageName = message ?? (channel && channel.messages.length === 1 ? channel.messages[0].name : null);
  const publish = useMutation({
    mutationFn: () =>
      tryKafkaPublish(contractId, versionId, {
        environmentId,
        channel: channelKey ?? "",
        message: messageName,
        key: key.trim() === "" ? null : key,
        headers: rowsToRecord(headers),
        payload,
      }),
    onSuccess: (r) => {
      setPublished(r);
      setRead(null);
    },
  });
  const tail = useMutation({
    mutationFn: () => tryKafkaRead(contractId, versionId, { environmentId, channel: channelKey ?? "", message: messageName, limit: Number(limit) || 10 }),
    onSuccess: (r) => {
      setRead(r);
      setPublished(null);
    },
  });
  const error = publish.error ?? tail.error;
  const needsMessage = (channel?.messages.length ?? 0) > 1 && messageName == null;
  return (
    <Stack gap="md">
      <Select
        label={t("tryIt.kafka.channel")}
        data={channels.map((c) => ({ value: c.channel, label: c.channel === c.address ? c.channel : `${c.channel} — ${c.address}` }))}
        value={channelKey}
        onChange={(v) => {
          setChannelKey(v);
          setMessage(null);
          setPublished(null);
          setRead(null);
        }}
        allowDeselect={false}
      />
      {channel && channel.messages.length > 1 && (
        <Select label={t("tryIt.kafka.message")} data={channel.messages.map((m) => m.name)} value={messageName} onChange={setMessage} allowDeselect={false} />
      )}
      {channel && (
        <>
          <Text size="xs" c="dimmed">
            {t("tryIt.kafka.actions", { actions: channel.actions.join(", ") || "—" })}
          </Text>
          {canWrite ? (
            <>
              <TextInput label={t("tryIt.kafka.key")} value={key} onChange={(e) => setKey(e.currentTarget.value)} />
              <KeyValueEditor label={t("tryIt.kafka.headers")} rows={headers} onChange={setHeaders} />
              <Text size="sm" fw={500}>
                {t("tryIt.kafka.payload")}
              </Text>
              <LazyCodeEditor value={payload} onChange={setPayload} format="json" ariaLabel={t("tryIt.kafka.payload")} minHeight={140} />
              <Group>
                <Button leftSection={<IconSend size={16} />} onClick={() => publish.mutate()} loading={publish.isPending} disabled={needsMessage || payload.trim() === ""}>
                  {t("tryIt.kafka.publish")}
                </Button>
              </Group>
            </>
          ) : (
            <Text size="sm" c="dimmed">
              {t("tryIt.kafka.publishWriters")}
            </Text>
          )}
          <Group align="flex-end">
            <NumberInput label={t("tryIt.kafka.limit")} min={1} max={50} value={limit} onChange={setLimit} w={120} />
            <Button variant="default" leftSection={<IconDownload size={16} />} onClick={() => tail.mutate()} loading={tail.isPending} disabled={needsMessage}>
              {t("tryIt.kafka.read")}
            </Button>
          </Group>
        </>
      )}
      {error && (
        <Alert color="red" variant="light" role="alert">
          {tryErrorMessage(error, t)}
        </Alert>
      )}
      {published && (
        <Stack gap="sm" role="region" aria-label={t("tryIt.kafka.publishedAria")}>
          <Alert color="teal" variant="light">
            {t("tryIt.kafka.published", { topic: published.topic, partition: published.partition, offset: published.offset })}
          </Alert>
          <FindingsPanel findings={published.conformance.findings} mode="stored" />
        </Stack>
      )}
      {read && (
        <Stack gap="sm" role="region" aria-label={t("tryIt.kafka.records", { topic: read.topic })}>
          <Group gap="xs">
            <Text size="sm" fw={500}>
              {t("tryIt.kafka.records", { topic: read.topic })}
            </Text>
            <Text size="xs" c="dimmed">
              {read.reachedEnd ? t("tryIt.kafka.reachedEnd") : t("tryIt.kafka.notEnd")} · {t("tryIt.duration", { ms: read.durationMs })}
            </Text>
          </Group>
          {read.messages.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("tryIt.kafka.noRecords", { topic: read.topic })}
            </Text>
          )}
          {read.messages.map((r) => (
            <RecordCard key={`${r.partition}-${r.offset}`} record={r} />
          ))}
          <FindingsPanel findings={read.conformance.findings} mode="stored" />
        </Stack>
      )}
    </Stack>
  );
}
