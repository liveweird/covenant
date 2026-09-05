import { Badge, Code, Collapse, Group, Stack, Table, Text, UnstyledButton } from "@mantine/core";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import type { ConformanceReport } from "../api/tryIt";
import { statusColor } from "../utils/tryIt";
import FindingsPanel from "./FindingsPanel";
import LazyCodeEditor from "./LazyCodeEditor";

/**
 * An HTTP try's observation: the status (teal 2xx, gray 3xx, orange 4xx, red 5xx — text-first,
 * never colour-only), the duration, a collapsed header table, the body in a read-only editor and
 * the CONFORMANCE findings through the same panel the stored report uses.
 */
export default function TryResponseView({
  url,
  status,
  durationMs,
  headers,
  body,
  bodyTruncated,
  conformance,
}: {
  url: string;
  status: number;
  durationMs: number;
  headers: Record<string, string>;
  body: string | null | undefined;
  bodyTruncated: boolean;
  conformance: ConformanceReport;
}) {
  const { t } = useTranslation();
  const [headersOpen, setHeadersOpen] = useState(false);
  const entries = Object.entries(headers);
  const json = /json/i.test(headers["content-type"] ?? "");
  return (
    <Stack gap="sm" role="region" aria-label={t("tryIt.http.response")}>
      <Group gap="sm">
        <Badge color={statusColor(status)} variant="light" size="lg">
          {t("tryIt.http.status", { status })}
        </Badge>
        <Text size="sm" c="dimmed">
          {t("tryIt.duration", { ms: durationMs })}
        </Text>
        <Code>{url}</Code>
      </Group>
      <UnstyledButton onClick={() => setHeadersOpen((o) => !o)} aria-expanded={headersOpen} fz="sm" fw={500}>
        {t("tryIt.http.responseHeaders")} ({entries.length})
      </UnstyledButton>
      <Collapse expanded={headersOpen}>
        <Table fz="xs">
          <Table.Tbody>
            {entries.map(([name, value]) => (
              <Table.Tr key={name}>
                <Table.Td fw={500}>{name}</Table.Td>
                <Table.Td style={{ wordBreak: "break-all" }}>{value}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Collapse>
      <Text size="sm" fw={500}>
        {t("tryIt.http.responseBody")}
      </Text>
      {body == null || body === "" ? (
        <Text size="sm" c="dimmed">
          {t("tryIt.http.noBody")}
        </Text>
      ) : (
        <LazyCodeEditor value={body} format={json ? "json" : "yaml"} readOnly ariaLabel={t("tryIt.http.responseBody")} minHeight={160} />
      )}
      {bodyTruncated && (
        <Text size="xs" c="orange">
          {t("tryIt.http.truncated")}
        </Text>
      )}
      <FindingsPanel findings={conformance.findings} mode="stored" />
    </Stack>
  );
}
