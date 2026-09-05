import { Button, Select, Stack, Text, TextInput } from "@mantine/core";
import { useMutation } from "@tanstack/react-query";
import { IconSend } from "@tabler/icons-react";
import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import TryError from "./TryError";
import { tryHttp, type HttpOperationSummary, type TryHttpResult } from "../api/tryIt";
import { rowsToRecord, templateParams, type KeyValueRow } from "../utils/tryIt";
import KeyValueEditor from "./KeyValueEditor";
import LazyCodeEditor from "./LazyCodeEditor";
import TryResponseView from "./TryResponseView";

/** The OpenAPI leg: pick an operation, bind its path template, add query/headers, a body when declared, Send. */
export default function TryHttpPanel({
  contractId,
  versionId,
  environmentId,
  operations,
}: {
  contractId: number;
  versionId: number;
  environmentId: number;
  operations: readonly HttpOperationSummary[];
}) {
  const { t } = useTranslation();
  const [selected, setSelected] = useState<string | null>(operations.length === 1 ? "0" : null);
  const [pathParams, setPathParams] = useState<Record<string, string>>({});
  const [query, setQuery] = useState<KeyValueRow[]>([]);
  const [headers, setHeaders] = useState<KeyValueRow[]>([]);
  const [contentType, setContentType] = useState<string | null>(null);
  const [body, setBody] = useState("");
  const [result, setResult] = useState<TryHttpResult | null>(null);
  const operation = selected == null ? null : operations[Number(selected)];
  const params = useMemo(() => (operation ? templateParams(operation.path) : []), [operation]);
  const options = operations.map((op, i) => ({
    value: String(i),
    label: `${op.method} ${op.path}${op.summary ? ` — ${op.summary}` : ""}`,
  }));
  const send = useMutation({
    mutationFn: () => {
      if (!operation) throw new Error("no operation");
      const mediaTypes = operation.requestBody?.mediaTypes ?? [];
      return tryHttp(contractId, versionId, {
        environmentId,
        method: operation.method,
        path: operation.path,
        pathParams,
        query: rowsToRecord(query),
        headers: rowsToRecord(headers),
        contentType: operation.requestBody ? contentType ?? mediaTypes[0] ?? null : null,
        body: operation.requestBody && body.trim() !== "" ? body : null,
      });
    },
    onSuccess: setResult,
  });
  const missing = params.some((p) => !pathParams[p]?.trim());
  return (
    <Stack gap="md">
      <Select
        label={t("tryIt.http.operation")}
        data={options}
        value={selected}
        onChange={(v) => {
          setSelected(v);
          setResult(null);
          setContentType(null);
        }}
        searchable
        allowDeselect={false}
      />
      {operation && (
        <>
          {params.map((p) => (
            <TextInput
              key={p}
              label={t("tryIt.http.pathParam", { name: p })}
              required
              value={pathParams[p] ?? ""}
              onChange={(e) => setPathParams({ ...pathParams, [p]: e.currentTarget.value })}
            />
          ))}
          <KeyValueEditor label={t("tryIt.http.query")} rows={query} onChange={setQuery} />
          <KeyValueEditor
            label={t("tryIt.http.headers")}
            rows={headers}
            onChange={setHeaders}
            hint={operation.securityHeaders.length > 0 ? t("tryIt.http.headersHint", { names: operation.securityHeaders.join(", ") }) : t("tryIt.http.headersPlain")}
          />
          {operation.requestBody && (
            <>
              <Select
                label={t("tryIt.http.contentType")}
                data={operation.requestBody.mediaTypes}
                value={contentType ?? operation.requestBody.mediaTypes[0] ?? null}
                onChange={setContentType}
                allowDeselect={false}
              />
              <Text size="sm" fw={500}>
                {t("tryIt.http.body")}
              </Text>
              <LazyCodeEditor value={body} onChange={setBody} format="json" ariaLabel={t("tryIt.http.body")} minHeight={140} />
            </>
          )}
          <Button leftSection={<IconSend size={16} />} onClick={() => send.mutate()} loading={send.isPending} disabled={missing}>
            {t("tryIt.http.send")}
          </Button>
        </>
      )}
      <TryError error={send.error} />
      {result && (
        <TryResponseView
          url={result.url}
          status={result.status}
          durationMs={result.durationMs}
          headers={result.headers}
          body={result.body}
          bodyTruncated={result.bodyTruncated}
          conformance={result.conformance}
        />
      )}
    </Stack>
  );
}
