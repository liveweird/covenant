import { Alert, Box, Button, Code, Group, NumberInput, Select, Stack, Table, Text } from "@mantine/core";
import { useMutation } from "@tanstack/react-query";
import { IconDatabaseSearch } from "@tabler/icons-react";
import { useState } from "react";
import { useTranslation } from "react-i18next";
import { trySql, type SqlDatasetSummary, type TrySqlResult } from "../api/tryIt";
import { tryErrorMessage } from "../utils/tryIt";
import FindingsPanel from "./FindingsPanel";

const TABLE_LIKE = new Set(["table", "view"]);

/** The ODCS leg: pick a table-like dataset, a row limit, Run — the sample and the column conformance come back. */
export default function TrySqlPanel({
  contractId,
  versionId,
  environmentId,
  datasets,
}: {
  contractId: number;
  versionId: number;
  environmentId: number;
  datasets: readonly SqlDatasetSummary[];
}) {
  const { t } = useTranslation();
  const tables = datasets.filter((d) => d.physicalType == null || TABLE_LIKE.has(d.physicalType.toLowerCase()));
  const [dataset, setDataset] = useState<string | null>(tables.length === 1 ? tables[0].name : null);
  const [limit, setLimit] = useState<string | number>(50);
  const [result, setResult] = useState<TrySqlResult | null>(null);
  const run = useMutation({
    mutationFn: () => trySql(contractId, versionId, { environmentId, dataset: dataset ?? "", limit: Number(limit) || 50 }),
    onSuccess: setResult,
  });
  return (
    <Stack gap="md">
      <Select
        label={t("tryIt.sql.dataset")}
        data={tables.map((d) => ({ value: d.name, label: d.physicalName && d.physicalName !== d.name ? `${d.name} — ${d.physicalName}` : d.name }))}
        value={dataset}
        onChange={(v) => {
          setDataset(v);
          setResult(null);
        }}
        allowDeselect={false}
      />
      <Group align="flex-end">
        <NumberInput label={t("tryIt.sql.limit")} min={1} max={200} value={limit} onChange={setLimit} w={120} />
        <Button leftSection={<IconDatabaseSearch size={16} />} onClick={() => run.mutate()} loading={run.isPending} disabled={dataset == null}>
          {t("tryIt.sql.run")}
        </Button>
      </Group>
      {run.isError && (
        <Alert color="red" variant="light" role="alert">
          {tryErrorMessage(run.error, t)}
        </Alert>
      )}
      {result && (
        <Stack gap="sm" role="region" aria-label={t("tryIt.sql.result")}>
          <Group gap="xs">
            <Code>{result.statement}</Code>
            <Text size="xs" c="dimmed">
              {t("tryIt.sql.rows", { count: result.rows.length })} · {t("tryIt.duration", { ms: result.durationMs })}
            </Text>
          </Group>
          {result.truncated && (
            <Text size="xs" c="orange">
              {t("tryIt.sql.truncated")}
            </Text>
          )}
          {result.columns.length > 0 && (
            <Box style={{ overflowX: "auto" }}>
              <Table fz="xs" aria-label={t("tryIt.sql.tableAria")}>
                <Table.Thead>
                  <Table.Tr>
                    {result.columns.map((c) => (
                      <Table.Th key={c.name}>
                        {c.name}{" "}
                        <Text span c="dimmed" fw={400}>
                          {c.dbType}
                          {c.nullable ? "?" : ""}
                        </Text>
                      </Table.Th>
                    ))}
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {result.rows.length === 0 && (
                    <Table.Tr>
                      <Table.Td colSpan={result.columns.length} c="dimmed">
                        {t("tryIt.sql.noRows")}
                      </Table.Td>
                    </Table.Tr>
                  )}
                  {result.rows.map((row, i) => (
                    <Table.Tr key={i}>
                      {row.map((cell, j) => (
                        <Table.Td key={j} style={{ whiteSpace: "nowrap", maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis" }}>
                          {cell == null ? (
                            <Text span c="dimmed" fs="italic">
                              {t("tryIt.sql.null")}
                            </Text>
                          ) : (
                            cell
                          )}
                        </Table.Td>
                      ))}
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Box>
          )}
          <FindingsPanel findings={result.conformance.findings} mode="stored" />
        </Stack>
      )}
    </Stack>
  );
}
