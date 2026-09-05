import { Badge, Code, Group, Paper, Stack, Table, Tabs, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ExampleView, MediaTypeView, OperationView, ResponseView, SecurityRequirementView } from "../api/versions";
import { statusClassColor } from "../utils/httpMethods";
import MarkdownView from "./MarkdownView";
import MethodBadge from "./MethodBadge";
import SchemaTree from "./SchemaTree";

const LOCATIONS = ["path", "query", "header", "cookie"] as const;

function Examples({ examples }: { examples: readonly ExampleView[] }) {
  if (examples.length === 0) return null;
  return (
    <Stack gap={4}>
      {examples.map((e) => (
        <Stack key={e.name} gap={0}>
          <Text size="xs" c="dimmed">
            {e.name}
            {e.summary ? ` — ${e.summary}` : ""}
          </Text>
          <Code block fz="xs" style={{ whiteSpace: "pre-wrap", wordBreak: "break-all" }}>
            {e.value}
          </Code>
        </Stack>
      ))}
    </Stack>
  );
}

/** One tab per media type — the schema tree and the examples of the chosen one. */
function MediaTypes({ content, schemaAnchors }: { content: readonly MediaTypeView[]; schemaAnchors: ReadonlyMap<string, string> }) {
  const { t } = useTranslation();
  if (content.length === 0) return null;
  return (
    <Tabs defaultValue={content[0].mediaType} keepMounted={false}>
      <Tabs.List>
        {content.map((m) => (
          <Tabs.Tab key={m.mediaType} value={m.mediaType} fz="xs">
            {m.mediaType}
          </Tabs.Tab>
        ))}
      </Tabs.List>
      {content.map((m) => (
        <Tabs.Panel key={m.mediaType} value={m.mediaType} pt="xs">
          <Stack gap="xs">
            {m.schema ? (
              <SchemaTree node={m.schema} schemaAnchors={schemaAnchors} />
            ) : (
              <Text size="xs" c="dimmed">
                {t("reader.noSchema")}
              </Text>
            )}
            <Examples examples={m.examples} />
          </Stack>
        </Tabs.Panel>
      ))}
    </Tabs>
  );
}

function Security({ security, inherited }: { security: OperationView["security"]; inherited: readonly SecurityRequirementView[] }) {
  const { t } = useTranslation();
  const effective = security ?? inherited;
  const label = security == null ? t("reader.security.inherited") : security.length === 0 ? t("reader.security.none") : t("reader.security.explicit");
  return (
    <Group gap={6} wrap="wrap">
      <Text size="xs" c="dimmed">
        {t("reader.security.title")}: {label}
      </Text>
      {effective.map((req, i) => (
        <Badge key={i} variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
          {req.schemes.map((s) => (s.scopes.length > 0 ? `${s.name} [${s.scopes.join(", ")}]` : s.name)).join(" + ") || "—"}
        </Badge>
      ))}
    </Group>
  );
}

function Response({ response, schemaAnchors }: { response: ResponseView; schemaAnchors: ReadonlyMap<string, string> }) {
  const { t } = useTranslation();
  return (
    <Stack gap={4} data-pointer={response.pointer}>
      <Group gap="xs" align="baseline">
        <Badge color={statusClassColor(response.status)} variant="light" size="sm" radius="sm" ff="monospace">
          {response.status}
        </Badge>
        {response.description && <Text size="sm">{response.description}</Text>}
      </Group>
      {response.headers.length > 0 && (
        <Table fz="xs" withRowBorders={false} aria-label={t("reader.responseHeadersAria", { status: response.status })}>
          <Table.Tbody>
            {response.headers.map((h) => (
              <Table.Tr key={h.name}>
                <Table.Td ff="monospace" fw={500}>
                  {h.name}
                  {h.required ? " *" : ""}
                </Table.Td>
                <Table.Td c="dimmed">{h.schema?.types.join(" | ") ?? ""}</Table.Td>
                <Table.Td>{h.description}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <MediaTypes content={response.content} schemaAnchors={schemaAnchors} />
    </Stack>
  );
}

/** One OpenAPI operation: method + path, summary and description, parameters by location, the request body, the responses, the security. */
export default function OperationCard({
  operation,
  id,
  inheritedSecurity,
  schemaAnchors,
}: {
  operation: OperationView;
  id: string;
  inheritedSecurity: readonly SecurityRequirementView[];
  schemaAnchors: ReadonlyMap<string, string>;
}) {
  const { t } = useTranslation();
  const grouped = LOCATIONS.map((loc) => [loc, operation.parameters.filter((p) => p.location === loc)] as const).filter(([, list]) => list.length > 0);
  return (
    <Paper withBorder p="md" radius="md" component="article" id={id} data-pointer={operation.pointer} aria-labelledby={`${id}-title`}>
      <Stack gap="sm">
        <Group gap="sm" align="baseline" wrap="wrap">
          <MethodBadge method={operation.method} />
          <Title order={4} size="h5" id={`${id}-title`} ff="monospace" td={operation.deprecated ? "line-through" : undefined}>
            {operation.path}
          </Title>
          {operation.deprecated && (
            <Badge color="orange" variant="light" size="xs">
              {t("reader.deprecated")}
            </Badge>
          )}
          {operation.operationId && (
            <Text size="xs" c="dimmed" ff="monospace">
              {operation.operationId}
            </Text>
          )}
        </Group>
        {operation.summary && <Text fw={500}>{operation.summary}</Text>}
        {operation.description && <MarkdownView>{operation.description}</MarkdownView>}
        {operation.externalDocs && (
          <Text size="xs" c="dimmed">
            {operation.externalDocs.description ?? t("reader.externalDocs")}: {operation.externalDocs.url}
          </Text>
        )}
        {grouped.length > 0 && (
          <Stack gap={4}>
            <Title order={5} size="sm">
              {t("reader.parameters")}
            </Title>
            <Table fz="sm" withRowBorders={false} aria-label={t("reader.parametersAria")}>
              <Table.Tbody>
                {grouped.flatMap(([loc, list]) =>
                  list.map((p) => (
                    <Table.Tr key={`${loc}-${p.name}`} data-pointer={p.pointer}>
                      <Table.Td ff="monospace" fw={500} style={{ whiteSpace: "nowrap" }}>
                        {p.name}
                        {p.required && (
                          <Text span c="red" aria-label={t("reader.schema.required")}>
                            {" *"}
                          </Text>
                        )}
                      </Table.Td>
                      <Table.Td c="dimmed" style={{ whiteSpace: "nowrap" }}>
                        {t(`reader.location.${loc}`)}
                      </Table.Td>
                      <Table.Td>
                        {p.schema ? <SchemaTree node={p.schema} schemaAnchors={schemaAnchors} /> : <MediaTypes content={p.content} schemaAnchors={schemaAnchors} />}
                        {p.description && (
                          <Text size="xs" c="dimmed">
                            {p.description}
                          </Text>
                        )}
                        <Examples examples={p.examples} />
                      </Table.Td>
                    </Table.Tr>
                  )),
                )}
              </Table.Tbody>
            </Table>
          </Stack>
        )}
        {operation.requestBody && (
          <Stack gap={4} data-pointer={operation.requestBody.pointer}>
            <Title order={5} size="sm">
              {t("reader.requestBody")}
              {operation.requestBody.required ? " *" : ""}
            </Title>
            {operation.requestBody.description && (
              <Text size="sm" c="dimmed">
                {operation.requestBody.description}
              </Text>
            )}
            <MediaTypes content={operation.requestBody.content} schemaAnchors={schemaAnchors} />
          </Stack>
        )}
        <Stack gap={4}>
          <Title order={5} size="sm">
            {t("reader.responses")}
          </Title>
          {operation.responses.length === 0 && (
            <Text size="xs" c="dimmed">
              {t("reader.noResponses")}
            </Text>
          )}
          {operation.responses.map((r) => (
            <Response key={r.status} response={r} schemaAnchors={schemaAnchors} />
          ))}
        </Stack>
        <Security security={operation.security} inherited={inheritedSecurity} />
      </Stack>
    </Paper>
  );
}
