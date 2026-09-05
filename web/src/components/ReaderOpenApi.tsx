import { Grid, Stack, Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { OpenApiModel, OperationView } from "../api/versions";
import OperationCard from "./OperationCard";
import ReaderInfoHeader from "./ReaderInfoHeader";
import { schemaAnchors } from "../utils/readerAnchors";
import ReaderSchemas from "./ReaderSchemas";
import ReaderSection from "./ReaderSection";
import ReaderSecuritySchemes from "./ReaderSecuritySchemes";
import ReaderToc, { type TocEntry } from "./ReaderToc";

const UNTAGGED = "__untagged";

/** Operations grouped by their first tag (declared order, undeclared appended by the server), untagged ones last. */
function groupByTag(model: OpenApiModel): { key: string; label: string | null; operations: OperationView[] }[] {
  const groups = model.tags.map((tag) => ({ key: tag.name, label: tag.name as string | null, operations: [] as OperationView[] }));
  const untagged: OperationView[] = [];
  for (const op of model.operations) {
    const group = groups.find((g) => g.key === op.tags[0]);
    if (group) group.operations.push(op);
    else untagged.push(op);
  }
  const out = groups.filter((g) => g.operations.length > 0);
  if (untagged.length > 0) out.push({ key: UNTAGGED, label: null, operations: untagged });
  return out;
}

/** The OpenAPI reader: info, servers, one section per tag of operation cards, webhooks, schemas, security. */
export default function ReaderOpenApi({ model, specVersion }: { model: OpenApiModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
  const groups = groupByTag(model);
  const anchors = schemaAnchors(model.schemas);
  const opId = (op: OperationView) => `op-${model.operations.indexOf(op)}`;
  const toc: TocEntry[] = [
    ...groups.map((g) => ({
      id: `tag-${g.key}`,
      label: g.label ?? t("reader.untagged"),
      children: g.operations.map((op) => ({ id: opId(op), label: `${op.method} ${op.path}` })),
    })),
    ...(model.webhooks.length > 0 ? [{ id: "reader-webhooks", label: t("reader.section.webhooks") }] : []),
    ...(model.schemas.length > 0 ? [{ id: "reader-schemas", label: t("reader.section.schemas") }] : []),
    ...(model.securitySchemes.length > 0 ? [{ id: "reader-security", label: t("reader.section.security") }] : []),
  ];
  return (
    <Grid gap="md">
      <Grid.Col span={{ base: 12, lg: 3 }} visibleFrom="lg">
        <ReaderToc entries={toc} />
      </Grid.Col>
      <Grid.Col span={{ base: 12, lg: 9 }}>
        <Stack gap="md">
          <ReaderInfoHeader info={model.info} externalDocs={model.externalDocs} tags={model.tags} specLabel={t("reader.spec.openapi", { version: specVersion ?? "" })} />
          {model.servers.length > 0 && (
            <ReaderSection id="reader-servers" title={t("reader.section.servers")} pointer="/servers">
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.servers")}>
                <Table.Tbody>
                  {model.servers.map((s) => (
                    <Table.Tr key={s.url}>
                      <Table.Td ff="monospace">{s.url}</Table.Td>
                      <Table.Td c="dimmed">
                        {s.description}
                        {s.variables.length > 0 && (
                          <Text size="xs" c="dimmed">
                            {s.variables.map((v) => `${v.name} = ${v.default}${v.enumValues.length > 0 ? ` (${v.enumValues.join(", ")})` : ""}`).join("; ")}
                          </Text>
                        )}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          {groups.map((g) => (
            <ReaderSection key={g.key} id={`tag-${g.key}`} title={g.label ?? t("reader.untagged")}>
              {g.label && model.tags.find((tag) => tag.name === g.label)?.description && (
                <Text size="sm" c="dimmed">
                  {model.tags.find((tag) => tag.name === g.label)?.description}
                </Text>
              )}
              <Stack gap="sm">
                {g.operations.map((op) => (
                  <OperationCard key={op.pointer} operation={op} id={opId(op)} inheritedSecurity={model.security} schemaAnchors={anchors} />
                ))}
              </Stack>
            </ReaderSection>
          ))}
          {model.operations.length === 0 && model.webhooks.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("reader.noOperations")}
            </Text>
          )}
          {model.webhooks.length > 0 && (
            <ReaderSection id="reader-webhooks" title={t("reader.section.webhooks")} pointer="/webhooks">
              <Stack gap="sm">
                {model.webhooks.map((op, i) => (
                  <OperationCard key={op.pointer} operation={op} id={`webhook-${i}`} inheritedSecurity={model.security} schemaAnchors={anchors} />
                ))}
              </Stack>
            </ReaderSection>
          )}
          <ReaderSchemas schemas={model.schemas} />
          <ReaderSecuritySchemes schemes={model.securitySchemes} />
        </Stack>
      </Grid.Col>
    </Grid>
  );
}
