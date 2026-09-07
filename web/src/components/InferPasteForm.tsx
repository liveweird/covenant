import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, NumberInput, Select, Stack, Text, TextInput } from "@mantine/core";
import { IconPlus } from "@tabler/icons-react";
import type { ContractType } from "../api/contracts";
import type { HttpExchangeSample, MessageBatchSample, RelationSample } from "../api/infer";
import { HTTP_METHODS } from "../utils/tryIt";
import LazyCodeEditor from "./LazyCodeEditor";

/** `"one JSON per line or a JSON array"` — a JSON array wins over the line-by-line reading. */
function parseJsonEntries(text: string): unknown[] {
  const trimmed = text.trim();
  if (trimmed === "") return [];
  if (trimmed.startsWith("[")) {
    const parsed: unknown = JSON.parse(trimmed);
    if (!Array.isArray(parsed)) throw new Error("not an array");
    return parsed;
  }
  return trimmed
    .split("\n")
    .map((line) => line.trim())
    .filter((line) => line !== "")
    .map((line) => JSON.parse(line) as unknown);
}

function HttpPasteFields({ onAdd }: { onAdd: (sample: HttpExchangeSample) => void }) {
  const { t } = useTranslation();
  const [method, setMethod] = useState("GET");
  const [url, setUrl] = useState("");
  const [status, setStatus] = useState<string | number>(200);
  const [requestContentType, setRequestContentType] = useState("application/json");
  const [requestBody, setRequestBody] = useState("");
  const [responseContentType, setResponseContentType] = useState("application/json");
  const [responseBody, setResponseBody] = useState("");

  function add() {
    onAdd({
      method,
      url: url.trim(),
      status: Number(status) || 200,
      requestContentType: requestBody.trim() ? requestContentType : null,
      requestBody: requestBody.trim() || null,
      responseContentType: responseBody.trim() ? responseContentType : null,
      responseBody: responseBody.trim() || null,
    });
    setUrl("");
    setRequestBody("");
    setResponseBody("");
  }

  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm" wrap="wrap">
        <Select label={t("infer.paste.method")} data={HTTP_METHODS} value={method} onChange={(v) => v && setMethod(v)} w={140} allowDeselect={false} />
        <TextInput
          label={t("infer.paste.url")}
          placeholder="https://api.example.test/orders/42"
          value={url}
          onChange={(e) => setUrl(e.currentTarget.value)}
          style={{ flex: 1, minWidth: 240 }}
        />
        <NumberInput label={t("infer.paste.status")} min={100} max={599} value={status} onChange={setStatus} w={120} />
      </Group>
      <Group grow align="flex-start" wrap="wrap">
        <Stack gap={4}>
          <TextInput label={t("infer.paste.requestContentType")} value={requestContentType} onChange={(e) => setRequestContentType(e.currentTarget.value)} />
          <Text size="sm" fw={500}>
            {t("infer.paste.requestBody")}
          </Text>
          <LazyCodeEditor value={requestBody} onChange={setRequestBody} format="json" ariaLabel={t("infer.paste.requestBody")} minHeight={120} />
        </Stack>
        <Stack gap={4}>
          <TextInput label={t("infer.paste.responseContentType")} value={responseContentType} onChange={(e) => setResponseContentType(e.currentTarget.value)} />
          <Text size="sm" fw={500}>
            {t("infer.paste.responseBody")}
          </Text>
          <LazyCodeEditor value={responseBody} onChange={setResponseBody} format="json" ariaLabel={t("infer.paste.responseBody")} minHeight={120} />
        </Stack>
      </Group>
      <Button leftSection={<IconPlus size={16} />} onClick={add} variant="default" disabled={!url.trim()} style={{ alignSelf: "flex-start" }}>
        {t("infer.paste.add")}
      </Button>
    </Stack>
  );
}

function MessagePasteFields({ onAdd }: { onAdd: (sample: MessageBatchSample) => void }) {
  const { t } = useTranslation();
  const [channel, setChannel] = useState("");
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  function add() {
    try {
      const payloads = parseJsonEntries(text).map((v) => JSON.stringify(v));
      if (payloads.length === 0) {
        setError(t("infer.paste.payloadsRequired"));
        return;
      }
      onAdd({ channel: channel.trim(), payloads });
      setChannel("");
      setText("");
      setError(null);
    } catch {
      setError(t("infer.paste.payloadsInvalid"));
    }
  }

  return (
    <Stack gap="sm">
      <TextInput label={t("infer.paste.channel")} placeholder="orders.v1.created" value={channel} onChange={(e) => setChannel(e.currentTarget.value)} />
      <Text size="sm" fw={500}>
        {t("infer.paste.payloads")}
      </Text>
      <LazyCodeEditor value={text} onChange={setText} format="json" ariaLabel={t("infer.paste.payloads")} minHeight={160} placeholder={t("infer.paste.payloadsHint")} />
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      <Button leftSection={<IconPlus size={16} />} onClick={add} variant="default" disabled={!channel.trim() || !text.trim()} style={{ alignSelf: "flex-start" }}>
        {t("infer.paste.add")}
      </Button>
    </Stack>
  );
}

function RelationPasteFields({ onAdd }: { onAdd: (sample: RelationSample) => void }) {
  const { t } = useTranslation();
  const [name, setName] = useState("");
  const [text, setText] = useState("");
  const [error, setError] = useState<string | null>(null);

  function add() {
    const trimmed = text.trim();
    if (trimmed === "") {
      setError(t("infer.paste.rowsRequired"));
      return;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(trimmed);
    } catch {
      setError(t("infer.paste.rowsInvalid"));
      return;
    }
    if (!Array.isArray(parsed) || parsed.length === 0) {
      setError(t(parsed instanceof Array ? "infer.paste.rowsRequired" : "infer.paste.rowsInvalid"));
      return;
    }
    onAdd({ name: name.trim(), rows: parsed.map((v) => JSON.stringify(v)) });
    setName("");
    setText("");
    setError(null);
  }

  return (
    <Stack gap="sm">
      <TextInput label={t("infer.paste.relation")} placeholder="public.users" value={name} onChange={(e) => setName(e.currentTarget.value)} />
      <Text size="sm" fw={500}>
        {t("infer.paste.rows")}
      </Text>
      <LazyCodeEditor value={text} onChange={setText} format="json" ariaLabel={t("infer.paste.rows")} minHeight={160} placeholder={t("infer.paste.rowsHint")} />
      {error && (
        <Alert color="red" variant="light">
          {error}
        </Alert>
      )}
      <Button leftSection={<IconPlus size={16} />} onClick={add} variant="default" disabled={!name.trim() || !text.trim()} style={{ alignSelf: "flex-start" }}>
        {t("infer.paste.add")}
      </Button>
    </Stack>
  );
}

/** One sample by hand, its shape driven by the contract type: an HTTP exchange, an AsyncAPI
 * payload batch (a JSON array or one-per-line), or an ODCS relation described by its rows. */
export default function InferPasteForm({
  type,
  onAdd,
}: {
  type: ContractType;
  onAdd: (sample: HttpExchangeSample | MessageBatchSample | RelationSample) => void;
}) {
  if (type === "OPENAPI") return <HttpPasteFields onAdd={onAdd} />;
  if (type === "ASYNCAPI") return <MessagePasteFields onAdd={onAdd} />;
  return <RelationPasteFields onAdd={onAdd} />;
}
