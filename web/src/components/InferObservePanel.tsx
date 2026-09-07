import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, NumberInput, Select, Stack, Text, TextInput } from "@mantine/core";
import { useMutation, useQuery } from "@tanstack/react-query";
import { IconDatabaseSearch, IconSend } from "@tabler/icons-react";
import type { ContractType } from "../api/contracts";
import { listEnvironments, type EnvironmentResponse } from "../api/environments";
import {
  listObservableRelations,
  observeHttp,
  observeKafka,
  observeSql,
  type HttpExchangeSample,
  type MessageBatchSample,
  type RelationSample,
} from "../api/infer";
import { isString, useStoredState } from "../hooks/useStoredState";
import { HTTP_METHODS, hasTryTarget, rowsToRecord, tryErrorMessage, tryTargetOf, type KeyValueRow } from "../utils/tryIt";
import KeyValueEditor from "./KeyValueEditor";
import LazyCodeEditor from "./LazyCodeEditor";

type Sample = HttpExchangeSample | MessageBatchSample | RelationSample;

function environmentLabel(e: EnvironmentResponse, scoped: boolean): string {
  return scoped ? e.name : `${e.systemName} / ${e.name}`;
}

function ObserveHttpLeg({ environmentId, onAdd }: { environmentId: number; onAdd: (sample: HttpExchangeSample) => void }) {
  const { t } = useTranslation();
  const [method, setMethod] = useState("GET");
  const [path, setPath] = useState("");
  const [query, setQuery] = useState<KeyValueRow[]>([]);
  const [headers, setHeaders] = useState<KeyValueRow[]>([]);
  const [contentType, setContentType] = useState("application/json");
  const [body, setBody] = useState("");
  const observe = useMutation({
    mutationFn: () =>
      observeHttp({
        environmentId,
        method,
        path: path.trim(),
        query: rowsToRecord(query),
        headers: rowsToRecord(headers),
        contentType: body.trim() ? contentType : null,
        body: body.trim() || null,
      }),
  });
  const data = observe.data;
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm" wrap="wrap">
        <Select label={t("infer.observe.method")} data={HTTP_METHODS} value={method} onChange={(v) => v && setMethod(v)} w={140} allowDeselect={false} />
        <TextInput
          label={t("infer.observe.http.path")}
          placeholder="/orders/42"
          value={path}
          onChange={(e) => setPath(e.currentTarget.value)}
          style={{ flex: 1, minWidth: 200 }}
        />
      </Group>
      <KeyValueEditor label={t("tryIt.http.query")} rows={query} onChange={setQuery} />
      <KeyValueEditor label={t("tryIt.http.headers")} rows={headers} onChange={setHeaders} hint={t("infer.observe.credentialsHint")} />
      <TextInput label={t("tryIt.http.contentType")} value={contentType} onChange={(e) => setContentType(e.currentTarget.value)} w={260} />
      <LazyCodeEditor value={body} onChange={setBody} format="json" ariaLabel={t("tryIt.http.body")} minHeight={120} />
      <Button
        leftSection={<IconSend size={16} />}
        onClick={() => observe.mutate()}
        loading={observe.isPending}
        variant="default"
        disabled={!path.trim()}
        style={{ alignSelf: "flex-start" }}
      >
        {t("infer.observe.run")}
      </Button>
      {observe.isError && (
        <Alert color="red" variant="light">
          {tryErrorMessage(observe.error, t)}
        </Alert>
      )}
      {data && (
        <Group justify="space-between" align="center">
          <Text size="sm">{t("infer.observe.http.result", { status: data.sample.status })}</Text>
          <Button size="xs" variant="default" onClick={() => onAdd(data.sample)}>
            {t("infer.observe.addToSamples")}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function ObserveKafkaLeg({ environmentId, onAdd }: { environmentId: number; onAdd: (sample: MessageBatchSample) => void }) {
  const { t } = useTranslation();
  const [topic, setTopic] = useState("");
  const [limit, setLimit] = useState<string | number>(10);
  const observe = useMutation({
    mutationFn: () => observeKafka({ environmentId, topic: topic.trim(), limit: Number(limit) || 10 }),
  });
  const data = observe.data;
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm" wrap="wrap">
        <TextInput label={t("tryIt.kafka.channel")} value={topic} onChange={(e) => setTopic(e.currentTarget.value)} style={{ flex: 1, minWidth: 200 }} />
        <NumberInput label={t("tryIt.kafka.limit")} min={1} max={50} value={limit} onChange={setLimit} w={120} />
        <Button leftSection={<IconDatabaseSearch size={16} />} onClick={() => observe.mutate()} loading={observe.isPending} variant="default" disabled={!topic.trim()}>
          {t("infer.observe.run")}
        </Button>
      </Group>
      {observe.isError && (
        <Alert color="red" variant="light">
          {tryErrorMessage(observe.error, t)}
        </Alert>
      )}
      {data && (
        <Group justify="space-between" align="center">
          <Text size="sm">{t("infer.observe.kafka.result", { count: data.sample.payloads?.length ?? 0 })}</Text>
          <Button size="xs" variant="default" onClick={() => onAdd(data.sample)}>
            {t("infer.observe.addToSamples")}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

function ObserveSqlLeg({ environmentId, onAdd }: { environmentId: number; onAdd: (sample: RelationSample) => void }) {
  const { t } = useTranslation();
  const relations = useQuery({
    queryKey: ["contracts", "infer", "relations", environmentId],
    queryFn: () => listObservableRelations({ environmentId }),
  });
  const [relation, setRelation] = useState<string | null>(null);
  const observe = useMutation({
    mutationFn: () => observeSql({ environmentId, relation: relation ?? "" }),
  });
  const options = (relations.data?.relations ?? []).map((r) => {
    const qualified = r.schema ? `${r.schema}.${r.name}` : r.name;
    return { value: qualified, label: `${qualified} (${r.kind})` };
  });
  const data = observe.data;
  return (
    <Stack gap="sm">
      <Group align="flex-end" gap="sm" wrap="wrap">
        <Select
          label={t("tryIt.sql.dataset")}
          data={options}
          value={relation}
          onChange={setRelation}
          searchable
          allowDeselect={false}
          style={{ flex: 1, minWidth: 240 }}
        />
        <Button leftSection={<IconDatabaseSearch size={16} />} onClick={() => observe.mutate()} loading={observe.isPending} variant="default" disabled={!relation}>
          {t("infer.observe.describe")}
        </Button>
      </Group>
      {observe.isError && (
        <Alert color="red" variant="light">
          {tryErrorMessage(observe.error, t)}
        </Alert>
      )}
      {data && (
        <Group justify="space-between" align="center">
          <Text size="sm">{t("infer.observe.sql.result", { count: data.sample.columns?.length ?? 0 })}</Text>
          <Button size="xs" variant="default" onClick={() => onAdd(data.sample)}>
            {t("infer.observe.addToSamples")}
          </Button>
        </Group>
      )}
    </Stack>
  );
}

/**
 * Pull ONE sample through an Environment — the try-it trust boundary and code (an HTTP call, a
 * Kafka topic tail, a PostgreSQL relation described from its catalog) — and hand it to the sample
 * list on "Add to samples". The environment picker is `TryItDrawer`'s: environments of the
 * contract's system (or every system, for a brand-new contract), filtered to the leg's target,
 * the last choice per system remembered.
 */
export default function InferObservePanel({
  type,
  systemId,
  onAdd,
}: {
  type: ContractType;
  systemId: number | null;
  onAdd: (sample: Sample) => void;
}) {
  const { t } = useTranslation();
  const target = tryTargetOf(type);
  const environments = useQuery({
    queryKey: ["environments", "forSystem", systemId ?? "all"],
    queryFn: () => listEnvironments({ page: 1, pageSize: 100, systemId: systemId ?? undefined }),
  });
  const usable = (environments.data?.items ?? []).filter((e) => hasTryTarget(e, target));
  const [stored, setStored] = useStoredState(`tryIt.environment.${systemId ?? "all"}`, "", isString);
  const selected = usable.find((e) => String(e.id) === stored) ?? usable[0] ?? null;

  return (
    <Stack gap="md">
      <Select
        label={t("tryIt.environment")}
        data={usable.map((e) => ({ value: String(e.id), label: environmentLabel(e, systemId != null) }))}
        value={selected ? String(selected.id) : null}
        onChange={(v) => v && setStored(v)}
        placeholder={usable.length === 0 ? t("tryIt.noEnvironments", { target: t(`tryIt.target.${target}`) }) : undefined}
        disabled={usable.length === 0}
        allowDeselect={false}
      />
      {selected && target === "http" && <ObserveHttpLeg environmentId={selected.id} onAdd={onAdd} />}
      {selected && target === "kafka" && <ObserveKafkaLeg environmentId={selected.id} onAdd={onAdd} />}
      {selected && target === "postgres" && <ObserveSqlLeg environmentId={selected.id} onAdd={onAdd} />}
    </Stack>
  );
}
