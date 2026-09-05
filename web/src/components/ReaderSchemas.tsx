import { Accordion, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { NamedSchemaView } from "../api/versions";
import { schemaAnchorId, schemaAnchors } from "../utils/readerAnchors";
import ReaderSection from "./ReaderSection";
import SchemaTree from "./SchemaTree";

/** The document's reusable schemas, each behind an expander so a long list stays scannable. */
export default function ReaderSchemas({ schemas }: { schemas: readonly NamedSchemaView[] }) {
  const { t } = useTranslation();
  if (schemas.length === 0) return null;
  const anchors = schemaAnchors(schemas);
  return (
    <ReaderSection id="reader-schemas" title={t("reader.section.schemas")} pointer="/components/schemas">
      <Accordion multiple variant="separated" chevronPosition="left">
        {schemas.map((s, i) => (
          <Accordion.Item key={s.name} value={s.name} id={schemaAnchorId(i)} data-pointer={s.pointer}>
            <Accordion.Control>
              <Text ff="monospace" fw={500} size="sm">
                {s.name}
              </Text>
            </Accordion.Control>
            <Accordion.Panel>
              <SchemaTree node={s.schema} schemaAnchors={anchors} />
            </Accordion.Panel>
          </Accordion.Item>
        ))}
      </Accordion>
    </ReaderSection>
  );
}
