import { Anchor, Badge, Group, Stack, Text, Title } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ExternalDocsView, InfoView, TagView } from "../api/versions";
import MarkdownView from "./MarkdownView";

/** The document's own front matter: title, declared version, description, contact, license, terms, external docs, tags. */
export default function ReaderInfoHeader({ info, externalDocs, tags, specLabel }: { info: InfoView; externalDocs?: ExternalDocsView; tags: readonly TagView[]; specLabel: string }) {
  const { t } = useTranslation();
  return (
    <Stack gap="xs" id="reader-info" data-pointer="/info">
      <Group gap="sm" align="baseline">
        <Title order={3}>{info.title || t("reader.untitled")}</Title>
        <Badge variant="outline" color="gray" style={{ textTransform: "none" }}>
          {t("reader.declaredVersion", { version: info.version })}
        </Badge>
        <Badge variant="light" color="gray" style={{ textTransform: "none" }}>
          {specLabel}
        </Badge>
      </Group>
      {info.summary && <Text fw={500}>{info.summary}</Text>}
      {info.description && <MarkdownView>{info.description}</MarkdownView>}
      <Group gap="md" wrap="wrap">
        {info.contact && (info.contact.name || info.contact.email || info.contact.url) && (
          <Text size="sm" c="dimmed">
            {t("reader.contact")}: {info.contact.name}
            {info.contact.email && (
              <>
                {" "}
                <Anchor href={`mailto:${info.contact.email}`} size="sm">
                  {info.contact.email}
                </Anchor>
              </>
            )}
            {info.contact.url && (
              <>
                {" "}
                <Anchor href={info.contact.url} size="sm" target="_blank" rel="noreferrer">
                  {info.contact.url}
                </Anchor>
              </>
            )}
          </Text>
        )}
        {info.license && (
          <Text size="sm" c="dimmed">
            {t("reader.license")}:{" "}
            {info.license.url ? (
              <Anchor href={info.license.url} size="sm" target="_blank" rel="noreferrer">
                {info.license.name}
              </Anchor>
            ) : (
              info.license.name
            )}
            {info.license.identifier && ` (${info.license.identifier})`}
          </Text>
        )}
        {info.termsOfService && (
          <Anchor href={info.termsOfService} size="sm" target="_blank" rel="noreferrer">
            {t("reader.termsOfService")}
          </Anchor>
        )}
        {externalDocs && (
          <Anchor href={externalDocs.url} size="sm" target="_blank" rel="noreferrer">
            {externalDocs.description || t("reader.externalDocs")}
          </Anchor>
        )}
      </Group>
      {tags.length > 0 && (
        <Group gap={6}>
          {tags.map((tag) => (
            <Badge key={tag.name} variant="light" color="gray" style={{ textTransform: "none" }} title={tag.description ?? undefined}>
              {tag.name}
            </Badge>
          ))}
        </Group>
      )}
    </Stack>
  );
}
