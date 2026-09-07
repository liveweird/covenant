import { Stack, Table, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { OpenApiModel } from "../api/versions";
import OperationCard from "./OperationCard";
import ReaderInfoHeader from "./ReaderInfoHeader";
import { schemaAnchors } from "../utils/readerAnchors";
import { groupByTag, opId } from "../utils/readerToc";
import ReaderSchemas from "./ReaderSchemas";
import ReaderSection from "./ReaderSection";
import ReaderSecuritySchemes from "./ReaderSecuritySchemes";

/** The OpenAPI reader: info, servers, one section per tag of operation cards, webhooks, schemas, security. */
export default function ReaderOpenApi({ model, specVersion }: { model: OpenApiModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
  const groups = groupByTag(model);
  const anchors = schemaAnchors(model.schemas);
  return (
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
              <OperationCard key={op.pointer} operation={op} id={opId(model, op)} inheritedSecurity={model.security} schemaAnchors={anchors} />
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
  );
}
