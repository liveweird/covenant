import { Anchor, Badge, Code, Grid, Group, Paper, Stack, Table, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { AsyncApiModel, AsyncOperationView, ChannelView, MessageView } from "../api/versions";
import MarkdownView from "./MarkdownView";
import ReaderInfoHeader from "./ReaderInfoHeader";
import { schemaAnchors } from "../utils/readerAnchors";
import ReaderSchemas from "./ReaderSchemas";
import ReaderSection from "./ReaderSection";
import ReaderSecuritySchemes from "./ReaderSecuritySchemes";
import ReaderToc, { type TocEntry } from "./ReaderToc";
import SchemaTree from "./SchemaTree";

const messageAnchor = (model: AsyncApiModel, key: string | null | undefined) => {
  const index = key == null ? -1 : model.messages.findIndex((m) => m.key === key);
  return index >= 0 ? `message-${index}` : undefined;
};
const channelAnchor = (model: AsyncApiModel, name: string | null | undefined) => {
  const index = name == null ? -1 : model.channels.findIndex((c) => c.name === name);
  return index >= 0 ? `channel-${index}` : undefined;
};

function MessageLinks({ refs, model }: { refs: readonly { name: string; target?: string | null }[]; model: AsyncApiModel }) {
  return (
    <Group gap={6} wrap="wrap">
      {refs.map((r) => {
        const anchor = messageAnchor(model, r.target);
        return anchor ? (
          <Anchor key={r.name} href={`#${anchor}`} size="sm" ff="monospace">
            {r.name}
          </Anchor>
        ) : (
          <Badge key={r.name} variant="light" color="orange" size="xs" style={{ textTransform: "none" }}>
            {r.name}
          </Badge>
        );
      })}
    </Group>
  );
}

function Chips({ items }: { items: readonly string[] }) {
  if (items.length === 0) return null;
  return (
    <Group gap={4} wrap="wrap">
      {items.map((i) => (
        <Badge key={i} variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
          {i}
        </Badge>
      ))}
    </Group>
  );
}

function ChannelCard({ channel, id, model }: { channel: ChannelView; id: string; model: AsyncApiModel }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="md" radius="md" component="article" id={id} data-pointer={channel.pointer} aria-labelledby={`${id}-title`}>
      <Stack gap="xs">
        <Group gap="sm" align="baseline" wrap="wrap">
          <Title order={4} size="h5" id={`${id}-title`} ff="monospace">
            {channel.name}
          </Title>
          {channel.address && channel.address !== channel.name && (
            <Code fz="xs">{channel.address}</Code>
          )}
          {channel.title && <Text size="sm">{channel.title}</Text>}
        </Group>
        {channel.summary && <Text fw={500}>{channel.summary}</Text>}
        {channel.description && <MarkdownView>{channel.description}</MarkdownView>}
        {channel.parameters.length > 0 && (
          <Table fz="sm" withRowBorders={false} aria-label={t("reader.parametersAria")}>
            <Table.Tbody>
              {channel.parameters.map((p) => (
                <Table.Tr key={p.name}>
                  <Table.Td ff="monospace" fw={500}>
                    {`{${p.name}}`}
                  </Table.Td>
                  <Table.Td>
                    {p.description && (
                      <Text size="sm" c="dimmed">
                        {p.description}
                      </Text>
                    )}
                    {p.enumValues.length > 0 && <Chips items={p.enumValues} />}
                    {p.schema && <SchemaTree node={p.schema} />}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
        {channel.messages.length > 0 && (
          <Group gap="xs" align="baseline">
            <Text size="xs" c="dimmed">
              {t("reader.async.messages")}:
            </Text>
            <MessageLinks refs={channel.messages} model={model} />
          </Group>
        )}
        {channel.servers.length > 0 && (
          <Text size="xs" c="dimmed">
            {t("reader.async.servers")}: {channel.servers.join(", ")}
          </Text>
        )}
        {channel.bindings.length > 0 && (
          <Text size="xs" c="dimmed">
            {t("reader.async.bindings")}: {channel.bindings.join(", ")}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

function OperationRow({ operation, id, model }: { operation: AsyncOperationView; id: string; model: AsyncApiModel }) {
  const { t } = useTranslation();
  const channel = channelAnchor(model, operation.channel);
  const reply = channelAnchor(model, operation.reply);
  return (
    <Paper withBorder p="md" radius="md" component="article" id={id} data-pointer={operation.pointer} aria-labelledby={`${id}-title`}>
      <Stack gap="xs">
        <Group gap="sm" align="baseline" wrap="wrap">
          <Badge color={operation.action === "send" ? "covenant" : "teal"} variant="light" radius="sm" style={{ textTransform: "none" }}>
            {operation.action === "send" ? t("reader.async.send") : t("reader.async.receive")}
          </Badge>
          {operation.legacyAction && (
            <Text size="xs" c="dimmed">
              ({operation.legacyAction})
            </Text>
          )}
          <Title order={4} size="h5" id={`${id}-title`} ff="monospace">
            {operation.name}
          </Title>
          {operation.channel && (
            <Text size="sm">
              {t("reader.async.onChannel")}{" "}
              {channel ? (
                <Anchor href={`#${channel}`} size="sm" ff="monospace">
                  {operation.channel}
                </Anchor>
              ) : (
                <Code fz="xs">{operation.channel}</Code>
              )}
            </Text>
          )}
        </Group>
        {operation.summary && <Text fw={500}>{operation.summary}</Text>}
        {operation.description && <MarkdownView>{operation.description}</MarkdownView>}
        {operation.messages.length > 0 && (
          <Group gap="xs" align="baseline">
            <Text size="xs" c="dimmed">
              {t("reader.async.messages")}:
            </Text>
            <MessageLinks refs={operation.messages} model={model} />
          </Group>
        )}
        {operation.reply && (
          <Text size="xs" c="dimmed">
            {t("reader.async.replyOn")}{" "}
            {reply ? (
              <Anchor href={`#${reply}`} size="xs" ff="monospace">
                {operation.reply}
              </Anchor>
            ) : (
              operation.reply
            )}
          </Text>
        )}
        <Chips items={[...operation.tags, ...operation.security.map((s) => `${t("reader.security.title")}: ${s}`), ...operation.bindings]} />
      </Stack>
    </Paper>
  );
}

function MessageCard({ message, id, anchors }: { message: MessageView; id: string; anchors: ReadonlyMap<string, string> }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="md" radius="md" component="article" id={id} data-pointer={message.pointer} aria-labelledby={`${id}-title`}>
      <Stack gap="xs">
        <Group gap="sm" align="baseline" wrap="wrap">
          <Title order={4} size="h5" id={`${id}-title`} ff="monospace" td={message.deprecated ? "line-through" : undefined}>
            {message.name ?? message.key}
          </Title>
          {message.title && <Text size="sm">{message.title}</Text>}
          {message.contentType && (
            <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
              {message.contentType}
            </Badge>
          )}
          {message.schemaFormat && (
            <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
              {message.schemaFormat}
            </Badge>
          )}
          {message.inline && (
            <Badge variant="outline" color="gray" size="xs">
              {t("reader.async.inline")}
            </Badge>
          )}
          {message.deprecated && (
            <Badge color="orange" variant="light" size="xs">
              {t("reader.deprecated")}
            </Badge>
          )}
        </Group>
        {message.summary && <Text fw={500}>{message.summary}</Text>}
        {message.description && <MarkdownView>{message.description}</MarkdownView>}
        {message.headers && (
          <Stack gap={4}>
            <Title order={5} size="sm">
              {t("reader.async.headers")}
            </Title>
            <SchemaTree node={message.headers} schemaAnchors={anchors} />
          </Stack>
        )}
        <Stack gap={4}>
          <Title order={5} size="sm">
            {t("reader.async.payload")}
          </Title>
          {message.payload ? (
            <SchemaTree node={message.payload} schemaAnchors={anchors} />
          ) : (
            <Text size="xs" c="dimmed">
              {t("reader.async.noPayload")}
            </Text>
          )}
        </Stack>
        {message.correlationId && (
          <Text size="xs" c="dimmed">
            {t("reader.async.correlationId")}: <Code fz="xs">{message.correlationId}</Code>
          </Text>
        )}
        {message.examples.map((e) => (
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
        <Chips items={[...message.tags, ...message.bindings]} />
      </Stack>
    </Paper>
  );
}

/** The AsyncAPI reader: info, servers, channels, operations (3.x vocabulary, the 2.x verb dimmed), messages, schemas, security. */
export default function ReaderAsyncApi({ model, specVersion }: { model: AsyncApiModel; specVersion: string | null | undefined }) {
  const { t } = useTranslation();
  const anchors = schemaAnchors(model.schemas);
  const toc: TocEntry[] = [
    ...(model.servers.length > 0 ? [{ id: "reader-servers", label: t("reader.section.servers") }] : []),
    { id: "reader-channels", label: t("reader.section.channels"), children: model.channels.map((c, i) => ({ id: `channel-${i}`, label: c.name })) },
    { id: "reader-operations", label: t("reader.section.operations"), children: model.operations.map((o, i) => ({ id: `operation-${i}`, label: o.name })) },
    { id: "reader-messages", label: t("reader.section.messages"), children: model.messages.map((m, i) => ({ id: `message-${i}`, label: m.name ?? m.key })) },
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
          <ReaderInfoHeader info={model.info} externalDocs={model.externalDocs} tags={model.tags} specLabel={t("reader.spec.asyncapi", { version: specVersion ?? "" })} />
          {model.defaultContentType && (
            <Text size="xs" c="dimmed">
              {t("reader.async.defaultContentType")}: {model.defaultContentType}
            </Text>
          )}
          {model.servers.length > 0 && (
            <ReaderSection id="reader-servers" title={t("reader.section.servers")} pointer="/servers">
              <Table fz="sm" withRowBorders={false} aria-label={t("reader.section.servers")}>
                <Table.Tbody>
                  {model.servers.map((s) => (
                    <Table.Tr key={s.name}>
                      <Table.Td fw={500} ff="monospace">
                        {s.name}
                      </Table.Td>
                      <Table.Td ff="monospace">
                        {s.protocol ? `${s.protocol}://` : ""}
                        {s.host}
                        {s.pathname}
                      </Table.Td>
                      <Table.Td c="dimmed">
                        {s.description}
                        {s.protocolVersion && ` · ${s.protocolVersion}`}
                        {s.security.length > 0 && ` · ${t("reader.security.title")}: ${s.security.join(", ")}`}
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </ReaderSection>
          )}
          <ReaderSection id="reader-channels" title={t("reader.section.channels")} pointer="/channels">
            {model.channels.length === 0 && (
              <Text size="sm" c="dimmed">
                {t("reader.async.noChannels")}
              </Text>
            )}
            <Stack gap="sm">
              {model.channels.map((c, i) => (
                <ChannelCard key={c.pointer} channel={c} id={`channel-${i}`} model={model} />
              ))}
            </Stack>
          </ReaderSection>
          <ReaderSection id="reader-operations" title={t("reader.section.operations")} pointer="/operations">
            {model.operations.length === 0 && (
              <Text size="sm" c="dimmed">
                {t("reader.async.noOperations")}
              </Text>
            )}
            <Stack gap="sm">
              {model.operations.map((o, i) => (
                <OperationRow key={o.pointer} operation={o} id={`operation-${i}`} model={model} />
              ))}
            </Stack>
          </ReaderSection>
          <ReaderSection id="reader-messages" title={t("reader.section.messages")} pointer="/components/messages">
            {model.messages.length === 0 && (
              <Text size="sm" c="dimmed">
                {t("reader.async.noMessages")}
              </Text>
            )}
            <Stack gap="sm">
              {model.messages.map((m, i) => (
                <MessageCard key={m.key} message={m} id={`message-${i}`} anchors={anchors} />
              ))}
            </Stack>
          </ReaderSection>
          <ReaderSchemas schemas={model.schemas} />
          <ReaderSecuritySchemes schemes={model.securitySchemes} />
        </Stack>
      </Grid.Col>
    </Grid>
  );
}
