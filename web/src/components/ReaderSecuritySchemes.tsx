import { Badge, Group, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { SecuritySchemeView } from "../api/versions";
import KeyValueTable from "./KeyValueTable";
import ReaderSection from "./ReaderSection";

/** The document's security schemes — type, scheme/format or key location, OAuth flows with their scopes. */
export default function ReaderSecuritySchemes({ schemes }: { schemes: readonly SecuritySchemeView[] }) {
  const { t } = useTranslation();
  if (schemes.length === 0) return null;
  return (
    <ReaderSection id="reader-security" title={t("reader.section.security")} pointer="/components/securitySchemes">
      <Stack gap="sm">
        {schemes.map((s) => (
          <Stack key={s.name} gap={4} data-pointer={s.pointer}>
            <Group gap="xs" align="baseline">
              <Text fw={500} ff="monospace" size="sm">
                {s.name}
              </Text>
              <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
                {s.type}
                {s.scheme ? ` · ${s.scheme}` : ""}
                {s.bearerFormat ? ` (${s.bearerFormat})` : ""}
                {s.location ? ` · ${s.location}: ${s.paramName ?? ""}` : ""}
              </Badge>
            </Group>
            {s.description && (
              <Text size="sm" c="dimmed">
                {s.description}
              </Text>
            )}
            {s.openIdConnectUrl && (
              <Text size="xs" c="dimmed">
                {s.openIdConnectUrl}
              </Text>
            )}
            {s.flows.map((f) => (
              <Stack key={f.type} gap={2} pl="sm">
                <Text size="xs" fw={500}>
                  {f.type}
                  {f.authorizationUrl ? ` · ${f.authorizationUrl}` : ""}
                  {f.tokenUrl ? ` · ${f.tokenUrl}` : ""}
                </Text>
                <KeyValueTable rows={f.scopes} ariaLabel={t("reader.scopesAria", { flow: f.type })} />
              </Stack>
            ))}
          </Stack>
        ))}
      </Stack>
    </ReaderSection>
  );
}
