import { Code, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DatasetView, OdcsPropertyView, QualityView } from "../api/versions";
import KeyValueTable from "./KeyValueTable";
import MarkdownView from "./MarkdownView";
import PlainBadge from "./PlainBadge";
import ReaderSection from "./ReaderSection";

function Yes({ on, label }: { on: boolean; label: string }) {
  return on ? <Text span aria-label={label}>✓</Text> : null;
}

/** The property rows, nested properties indented and `aria-level`led, array items as a child row. */
function propertyRows(props: readonly OdcsPropertyView[], level: number, t: (k: string) => string): { p: OdcsPropertyView; level: number; label: string }[] {
  return props.flatMap((p) => {
    const own = { p, level, label: p.name };
    const items = p.items ? [{ p: p.items, level: level + 1, label: `${p.name}[] ${t("reader.odcs.items")}` }] : [];
    return [own, ...items, ...propertyRows(p.properties, level + 1, t), ...(p.items ? propertyRows(p.items.properties, level + 2, t) : [])];
  });
}

function QualityList({ quality }: { quality: readonly QualityView[] }) {
  const { t } = useTranslation();
  if (quality.length === 0) return null;
  return (
    <Stack gap={4}>
      <Title order={5} size="sm">
        {t("reader.odcs.quality")}
      </Title>
      {quality.map((q) => (
        <Group key={q.pointer} gap="xs" wrap="wrap" data-pointer={q.pointer}>
          {q.type && (
            <PlainBadge>
              {q.type}
            </PlainBadge>
          )}
          {q.severity && (
            <PlainBadge color={q.severity === "error" ? "red" : "orange"}>
              {q.severity}
            </PlainBadge>
          )}
          {q.dimension && (
            <PlainBadge outline>
              {q.dimension}
            </PlainBadge>
          )}
          <Text size="sm">{q.name ?? q.rule ?? q.description ?? ""}</Text>
          {q.rule && q.name && <Code fz="xs">{q.rule}</Code>}
          {q.query && <Code fz="xs">{q.query}</Code>}
          {q.thresholds.map((th) => (
            <Text key={th.key} size="xs" c="dimmed">
              {th.key} {th.value}
            </Text>
          ))}
          {q.description && q.name && (
            <Text size="xs" c="dimmed">
              {q.description}
            </Text>
          )}
        </Group>
      ))}
    </Stack>
  );
}

/** One ODCS dataset: its badges, description, the property table (nested rows `aria-level`led), quality and custom properties. */
export default function DatasetSection({ dataset, id }: { dataset: DatasetView; id: string }) {
  const { t } = useTranslation();
  const rows = propertyRows(dataset.properties, 1, (k) => t(k as "reader.odcs.items"));
  return (
    <ReaderSection id={id} title={dataset.businessName ? `${dataset.name} — ${dataset.businessName}` : dataset.name} pointer={dataset.pointer}>
      <Group gap="xs" wrap="wrap">
        {dataset.physicalType && (
          <PlainBadge>
            {dataset.physicalType}
          </PlainBadge>
        )}
        {dataset.physicalName && <Code fz="xs">{dataset.physicalName}</Code>}
        {dataset.tags.map((tag) => (
          <PlainBadge key={tag} outline>
            {tag}
          </PlainBadge>
        ))}
      </Group>
      {dataset.description && <MarkdownView>{dataset.description}</MarkdownView>}
      {dataset.dataGranularityDescription && (
        <Text size="sm" c="dimmed">
          {t("reader.odcs.granularity")}: {dataset.dataGranularityDescription}
        </Text>
      )}
      {rows.length > 0 && (
        <Table fz="sm" aria-label={t("reader.odcs.propertiesAria", { dataset: dataset.name })} style={{ overflowX: "auto" }}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t("reader.odcs.col.name")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.logicalType")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.physicalType")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.required")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.unique")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.primaryKey")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.partition")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.classification")}</Table.Th>
              <Table.Th>{t("reader.odcs.col.description")}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map(({ p, level, label }) => (
              <Table.Tr key={p.pointer} data-pointer={p.pointer} aria-level={level}>
                <Table.Td ff="monospace" fw={500} style={{ paddingLeft: `calc(var(--mantine-spacing-sm) + ${(level - 1) * 16}px)`, whiteSpace: "nowrap" }}>
                  {label}
                  {p.physicalName && p.physicalName !== p.name && (
                    <Text span size="xs" c="dimmed">
                      {" "}
                      ({p.physicalName})
                    </Text>
                  )}
                  {p.marker === "TRUNCATED" && (
                    <PlainBadge ml={4}>
                      {t("reader.schema.truncated")}
                    </PlainBadge>
                  )}
                </Table.Td>
                <Table.Td>
                  {p.logicalType}
                  {p.options.length > 0 && (
                    <Text size="xs" c="dimmed">
                      {p.options.map((o) => `${o.key}: ${o.value}`).join(", ")}
                    </Text>
                  )}
                </Table.Td>
                <Table.Td c="dimmed">{p.physicalType}</Table.Td>
                <Table.Td ta="center">
                  <Yes on={p.required} label={t("reader.odcs.col.required")} />
                </Table.Td>
                <Table.Td ta="center">
                  <Yes on={p.unique} label={t("reader.odcs.col.unique")} />
                </Table.Td>
                <Table.Td ta="center">
                  {p.primaryKey && <Text span>{p.primaryKeyPosition != null ? `PK ${p.primaryKeyPosition}` : "PK"}</Text>}
                </Table.Td>
                <Table.Td ta="center">
                  {p.partitioned && <Text span>{p.partitionKeyPosition != null ? `P ${p.partitionKeyPosition}` : "P"}</Text>}
                </Table.Td>
                <Table.Td c="dimmed">
                  {p.classification}
                  {p.criticalDataElement && (
                    <PlainBadge color="orange" ml={4}>
                      {t("reader.odcs.critical")}
                    </PlainBadge>
                  )}
                </Table.Td>
                <Table.Td>
                  {p.description}
                  {p.examples.length > 0 && (
                    <Text size="xs" c="dimmed">
                      {t("reader.schema.examples")}: {p.examples.join(", ")}
                    </Text>
                  )}
                  {p.transformLogic && (
                    <Text size="xs" c="dimmed">
                      {t("reader.odcs.transform")}: <Code fz="xs">{p.transformLogic}</Code>
                      {p.transformSourceObjects.length > 0 && ` ← ${p.transformSourceObjects.join(", ")}`}
                    </Text>
                  )}
                  {p.quality.length > 0 && <QualityList quality={p.quality} />}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <QualityList quality={dataset.quality} />
      <KeyValueTable rows={dataset.customProperties} ariaLabel={t("reader.odcs.customPropertiesAria", { name: dataset.name })} />
    </ReaderSection>
  );
}
