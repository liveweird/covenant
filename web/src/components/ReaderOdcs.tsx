import { Badge, Code, Grid, Group, Stack, Table, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { DatasetView, OdcsModel, OdcsPropertyView, QualityView } from "../api/versions";
import { LIFECYCLES } from "../utils/lifecycle";
import KeyValueTable from "./KeyValueTable";
import LifecyclePill from "./LifecyclePill";
import MarkdownView from "./MarkdownView";
import ReaderSection from "./ReaderSection";
import ReaderToc, { type TocEntry } from "./ReaderToc";

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
            <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
              {q.type}
            </Badge>
          )}
          {q.severity && (
            <Badge variant="light" color={q.severity === "error" ? "red" : "orange"} size="xs" style={{ textTransform: "none" }}>
              {q.severity}
            </Badge>
          )}
          {q.dimension && (
            <Badge variant="outline" color="gray" size="xs" style={{ textTransform: "none" }}>
              {q.dimension}
            </Badge>
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

function DatasetSection({ dataset, id }: { dataset: DatasetView; id: string }) {
  const { t } = useTranslation();
  const rows = propertyRows(dataset.properties, 1, (k) => t(k as "reader.odcs.items"));
  return (
    <ReaderSection id={id} title={dataset.businessName ? `${dataset.name} — ${dataset.businessName}` : dataset.name} pointer={dataset.pointer}>
      <Group gap="xs" wrap="wrap">
        {dataset.physicalType && (
          <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
            {dataset.physicalType}
          </Badge>
        )}
        {dataset.physicalName && <Code fz="xs">{dataset.physicalName}</Code>}
        {dataset.tags.map((tag) => (
          <Badge key={tag} variant="outline" color="gray" size="xs" style={{ textTransform: "none" }}>
            {tag}
          </Badge>
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
                    <Badge variant="light" color="gray" size="xs" ml={4}>
                      {t("reader.schema.truncated")}
                    </Badge>
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
                    <Badge variant="light" color="orange" size="xs" ml={4}>
                      {t("reader.odcs.critical")}
                    </Badge>
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

/** The ODCS reader: the contract header, description, datasets, servers, team, roles, SLA, support, price, custom properties. */
export default function ReaderOdcs({ model, specVersion }: { model: OdcsModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
  const status = model.status?.toUpperCase();
  const lifecycle = LIFECYCLES.find((l) => l === status);
  const toc: TocEntry[] = [
    ...(model.description ? [{ id: "reader-description", label: t("reader.section.description") }] : []),
    { id: "reader-datasets", label: t("reader.section.datasets"), children: model.datasets.map((d, i) => ({ id: `dataset-${i}`, label: d.name })) },
    ...(model.servers.length > 0 ? [{ id: "reader-servers", label: t("reader.section.servers") }] : []),
    ...(model.team.length > 0 ? [{ id: "reader-team", label: t("reader.section.team") }] : []),
    ...(model.roles.length > 0 ? [{ id: "reader-roles", label: t("reader.section.roles") }] : []),
    ...(model.slaProperties.length > 0 ? [{ id: "reader-sla", label: t("reader.section.sla") }] : []),
    ...(model.support.length > 0 ? [{ id: "reader-support", label: t("reader.section.support") }] : []),
    ...(model.price ? [{ id: "reader-price", label: t("reader.section.price") }] : []),
    ...(model.customProperties.length > 0 ? [{ id: "reader-custom", label: t("reader.section.customProperties") }] : []),
  ];
  return (
    <Grid gap="md">
      <Grid.Col span={{ base: 12, lg: 3 }} visibleFrom="lg">
        <ReaderToc entries={toc} />
      </Grid.Col>
      <Grid.Col span={{ base: 12, lg: 9 }}>
        <Stack gap="md">
          <Stack gap="xs" id="reader-info" data-pointer="/name">
            <Group gap="sm" align="baseline" wrap="wrap">
              <Title order={3}>{model.name ?? model.id ?? t("reader.untitled")}</Title>
              {model.version && (
                <Badge variant="outline" color="gray" style={{ textTransform: "none" }}>
                  {t("reader.declaredVersion", { version: model.version })}
                </Badge>
              )}
              <Badge variant="light" color="gray" style={{ textTransform: "none" }}>
                {t("reader.spec.odcs", { version: specVersion ?? "" })}
              </Badge>
              {lifecycle ? (
                <LifecyclePill lifecycle={lifecycle} size="sm" />
              ) : (
                model.status && (
                  <Badge variant="light" color="gray" style={{ textTransform: "none" }}>
                    {model.status}
                  </Badge>
                )
              )}
            </Group>
            <Group gap="md" wrap="wrap">
              {model.id && (
                <Text size="xs" c="dimmed">
                  id: <Code fz="xs">{model.id}</Code>
                </Text>
              )}
              {model.domain && (
                <Text size="xs" c="dimmed">
                  {t("reader.odcs.domain")}: {model.domain}
                </Text>
              )}
              {model.dataProduct && (
                <Text size="xs" c="dimmed">
                  {t("reader.odcs.dataProduct")}: {model.dataProduct}
                </Text>
              )}
              {model.tenant && (
                <Text size="xs" c="dimmed">
                  {t("reader.odcs.tenant")}: {model.tenant}
                </Text>
              )}
              {model.contractCreatedTs && (
                <Text size="xs" c="dimmed">
                  {t("reader.odcs.created")}: {model.contractCreatedTs}
                </Text>
              )}
            </Group>
            {model.tags.length > 0 && (
              <Group gap={6}>
                {model.tags.map((tag) => (
                  <Badge key={tag} variant="light" color="gray" style={{ textTransform: "none" }}>
                    {tag}
                  </Badge>
                ))}
              </Group>
            )}
          </Stack>
          {model.description && (
            <ReaderSection id="reader-description" title={t("reader.section.description")} pointer="/description">
              {model.description.purpose && <MarkdownView>{model.description.purpose}</MarkdownView>}
              {model.description.limitations && (
                <Text size="sm">
                  <Text span fw={500}>
                    {t("reader.odcs.limitations")}:
                  </Text>{" "}
                  {model.description.limitations}
                </Text>
              )}
              {model.description.usage && (
                <Text size="sm">
                  <Text span fw={500}>
                    {t("reader.odcs.usage")}:
                  </Text>{" "}
                  {model.description.usage}
                </Text>
              )}
              {model.description.authoritativeDefinitions.map((d) => (
                <Text key={d.url} size="xs" c="dimmed">
                  {d.type ? `${d.type}: ` : ""}
                  {d.url}
                </Text>
              ))}
              <KeyValueTable rows={model.description.customProperties} ariaLabel={t("reader.section.customProperties")} />
            </ReaderSection>
          )}
          <Stack gap="md" id="reader-datasets">
            {model.datasets.length === 0 && (
              <Text size="sm" c="dimmed">
                {t("reader.odcs.noDatasets")}
              </Text>
            )}
            {model.datasets.map((d, i) => (
              <DatasetSection key={d.pointer} dataset={d} id={`dataset-${i}`} />
            ))}
          </Stack>
          {model.servers.length > 0 && (
            <ReaderSection id="reader-servers" title={t("reader.section.servers")} pointer="/servers">
              {model.servers.map((s) => (
                <Stack key={s.pointer} gap={4} data-pointer={s.pointer}>
                  <Group gap="xs" align="baseline">
                    <Text fw={500} ff="monospace" size="sm">
                      {s.server}
                    </Text>
                    {s.type && (
                      <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
                        {s.type}
                      </Badge>
                    )}
                    {s.environment && (
                      <Badge variant="outline" color="gray" size="xs" style={{ textTransform: "none" }}>
                        {s.environment}
                      </Badge>
                    )}
                    {s.description && (
                      <Text size="sm" c="dimmed">
                        {s.description}
                      </Text>
                    )}
                  </Group>
                  <KeyValueTable rows={s.details} ariaLabel={t("reader.odcs.serverDetailsAria", { server: s.server })} />
                  {s.roles.length > 0 && (
                    <Text size="xs" c="dimmed">
                      {t("reader.section.roles")}: {s.roles.map((r) => `${r.role}${r.access ? ` (${r.access})` : ""}`).join(", ")}
                    </Text>
                  )}
                </Stack>
              ))}
            </ReaderSection>
          )}
          {model.team.length > 0 && (
            <ReaderSection id="reader-team" title={t("reader.section.team")} pointer="/team">
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.team")}>
                <Table.Tbody>
                  {model.team.map((m) => (
                    <Table.Tr key={m.username}>
                      <Table.Td fw={500}>{m.name ?? m.username}</Table.Td>
                      <Table.Td c="dimmed">{m.username}</Table.Td>
                      <Table.Td>{m.role}</Table.Td>
                      <Table.Td c="dimmed">
                        {m.dateIn}
                        {m.dateOut ? ` → ${m.dateOut}` : ""}
                        {m.replacedByUsername ? ` (${t("reader.odcs.replacedBy", { name: m.replacedByUsername })})` : ""}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          {model.roles.length > 0 && (
            <ReaderSection id="reader-roles" title={t("reader.section.roles")} pointer="/roles">
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.roles")}>
                <Table.Tbody>
                  {model.roles.map((r) => (
                    <Table.Tr key={r.role}>
                      <Table.Td fw={500}>{r.role}</Table.Td>
                      <Table.Td>{r.access}</Table.Td>
                      <Table.Td c="dimmed">{r.description}</Table.Td>
                      <Table.Td c="dimmed">
                        {r.firstLevelApprovers && `${t("reader.odcs.approvers")}: ${r.firstLevelApprovers}`}
                        {r.secondLevelApprovers && ` / ${r.secondLevelApprovers}`}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          {model.slaProperties.length > 0 && (
            <ReaderSection id="reader-sla" title={t("reader.section.sla")} pointer="/slaProperties">
              {model.slaDefaultElement && (
                <Text size="xs" c="dimmed">
                  {t("reader.odcs.slaDefaultElement")}: <Code fz="xs">{model.slaDefaultElement}</Code>
                </Text>
              )}
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.sla")}>
                <Table.Tbody>
                  {model.slaProperties.map((p, i) => (
                    <Table.Tr key={`${p.property}-${i}`}>
                      <Table.Td fw={500}>{p.property}</Table.Td>
                      <Table.Td>
                        {p.value}
                        {p.unit ? ` ${p.unit}` : ""}
                        {p.valueExt ? ` (${p.valueExt})` : ""}
                      </Table.Td>
                      <Table.Td c="dimmed">{p.element}</Table.Td>
                      <Table.Td c="dimmed">{p.driver}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          {model.support.length > 0 && (
            <ReaderSection id="reader-support" title={t("reader.section.support")} pointer="/support">
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.support")}>
                <Table.Tbody>
                  {model.support.map((s, i) => (
                    <Table.Tr key={`${s.channel}-${i}`}>
                      <Table.Td fw={500}>{s.channel}</Table.Td>
                      <Table.Td>{s.tool}</Table.Td>
                      <Table.Td c="dimmed">{s.scope}</Table.Td>
                      <Table.Td>{s.url ?? s.invitationUrl}</Table.Td>
                      <Table.Td c="dimmed">{s.description}</Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          {model.price && (
            <ReaderSection id="reader-price" title={t("reader.section.price")} pointer="/price">
              <Text size="sm">
                {model.price.priceAmount} {model.price.priceCurrency}
                {model.price.priceUnit ? ` / ${model.price.priceUnit}` : ""}
              </Text>
            </ReaderSection>
          )}
          {(model.customProperties.length > 0 || model.authoritativeDefinitions.length > 0) && (
            <ReaderSection id="reader-custom" title={t("reader.section.customProperties")} pointer="/customProperties">
              <KeyValueTable rows={model.customProperties} ariaLabel={t("reader.section.customProperties")} />
              {model.authoritativeDefinitions.map((d) => (
                <Text key={d.url} size="xs" c="dimmed">
                  {d.type ? `${d.type}: ` : ""}
                  {d.url}
                </Text>
              ))}
            </ReaderSection>
          )}
        </Stack>
      </Grid.Col>
    </Grid>
  );
}
