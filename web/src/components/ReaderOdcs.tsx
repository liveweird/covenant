import { Code, Grid, Group, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { OdcsModel } from "../api/versions";
import { LIFECYCLES } from "../utils/lifecycle";
import KeyValueTable from "./KeyValueTable";
import LifecyclePill from "./LifecyclePill";
import MarkdownView from "./MarkdownView";
import PlainBadge from "./PlainBadge";
import DatasetSection from "./ReaderOdcsDataset";
import ReaderSection from "./ReaderSection";
import ReaderTableSection from "./ReaderTableSection";
import ReaderToc, { type TocEntry } from "./ReaderToc";

/** The contract header: name, declared version, spec label, status (a lifecycle pill when it maps), identity fields, tags. */
function OdcsHeader({ model, specVersion }: { model: OdcsModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
  const status = model.status?.toUpperCase();
  const lifecycle = LIFECYCLES.find((l) => l === status);
  return (
    <Stack gap="xs" id="reader-info" data-pointer="/name">
      <Group gap="sm" align="baseline" wrap="wrap">
        <Title order={3}>{model.name ?? model.id ?? t("reader.untitled")}</Title>
        {model.version && (
          <PlainBadge outline size="sm">
            {t("reader.declaredVersion", { version: model.version })}
          </PlainBadge>
        )}
        <PlainBadge size="sm">
          {t("reader.spec.odcs", { version: specVersion ?? "" })}
        </PlainBadge>
        {lifecycle ? (
          <LifecyclePill lifecycle={lifecycle} size="sm" />
        ) : (
          model.status && (
            <PlainBadge size="sm">
              {model.status}
            </PlainBadge>
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
            <PlainBadge key={tag} size="sm">
              {tag}
            </PlainBadge>
          ))}
        </Group>
      )}
    </Stack>
  );
}

/** The ODCS reader: the contract header, description, datasets, servers, team, roles, SLA, support, price, custom properties. */
export default function ReaderOdcs({ model, specVersion }: { model: OdcsModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
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
          <OdcsHeader model={model} specVersion={specVersion} />
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
                      <PlainBadge>
                        {s.type}
                      </PlainBadge>
                    )}
                    {s.environment && (
                      <PlainBadge outline>
                        {s.environment}
                      </PlainBadge>
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
            <ReaderTableSection
              id="reader-team"
              title={t("reader.section.team")}
              pointer="/team"
              rows={model.team}
              rowKey={(m) => m.username}
              cells={(m) => [
                m.name ?? m.username,
                <Text span c="dimmed" size="sm" key="user">{m.username}</Text>,
                m.role,
                <Text span c="dimmed" size="sm" key="dates">
                  {m.dateIn}
                  {m.dateOut ? ` → ${m.dateOut}` : ""}
                  {m.replacedByUsername ? ` (${t("reader.odcs.replacedBy", { name: m.replacedByUsername })})` : ""}
                </Text>,
              ]}
            />
          )}
          {model.roles.length > 0 && (
            <ReaderTableSection
              id="reader-roles"
              title={t("reader.section.roles")}
              pointer="/roles"
              rows={model.roles}
              rowKey={(r) => r.role}
              cells={(r) => [
                r.role,
                r.access,
                <Text span c="dimmed" size="sm" key="desc">{r.description}</Text>,
                <Text span c="dimmed" size="sm" key="approvers">
                  {r.firstLevelApprovers && `${t("reader.odcs.approvers")}: ${r.firstLevelApprovers}`}
                  {r.secondLevelApprovers && ` / ${r.secondLevelApprovers}`}
                </Text>,
              ]}
            />
          )}
          {model.slaProperties.length > 0 && (
            <ReaderTableSection
              id="reader-sla"
              title={t("reader.section.sla")}
              pointer="/slaProperties"
              rows={model.slaProperties}
              rowKey={(p, i) => `${p.property}-${i}`}
              lead={
                model.slaDefaultElement && (
                  <Text size="xs" c="dimmed">
                    {t("reader.odcs.slaDefaultElement")}: <Code fz="xs">{model.slaDefaultElement}</Code>
                  </Text>
                )
              }
              cells={(p) => [
                p.property,
                `${p.value ?? ""}${p.unit ? ` ${p.unit}` : ""}${p.valueExt ? ` (${p.valueExt})` : ""}`,
                <Text span c="dimmed" size="sm" key="element">{p.element}</Text>,
                <Text span c="dimmed" size="sm" key="driver">{p.driver}</Text>,
              ]}
            />
          )}
          {model.support.length > 0 && (
            <ReaderTableSection
              id="reader-support"
              title={t("reader.section.support")}
              pointer="/support"
              rows={model.support}
              rowKey={(s, i) => `${s.channel}-${i}`}
              cells={(s) => [
                s.channel,
                s.tool,
                <Text span c="dimmed" size="sm" key="scope">{s.scope}</Text>,
                s.url ?? s.invitationUrl,
                <Text span c="dimmed" size="sm" key="desc">{s.description}</Text>,
              ]}
            />
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
