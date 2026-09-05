import { ActionIcon, Anchor, Badge, Box, Code, Group, Stack, Text } from "@mantine/core";
import { IconChevronDown, IconChevronRight } from "@tabler/icons-react";
import { useId, useState } from "react";
import { useTranslation } from "react-i18next";
import type { SchemaNode } from "../api/versions";

const MAX_ENUM_CHIPS = 20;
/** Nodes deeper than this start collapsed — the reader opens what it needs. */
const OPEN_DEPTH = 1;

function hasChildren(node: SchemaNode): boolean {
  return (
    node.properties.length > 0 ||
    node.items != null ||
    node.tupleItems.length > 0 ||
    node.additionalProperties != null ||
    node.allOf.length > 0 ||
    node.anyOf.length > 0 ||
    node.oneOf.length > 0 ||
    node.not != null
  );
}

function typeLabel(node: SchemaNode): string {
  const base = node.types.length > 0 ? node.types.join(" | ") : node.ref && node.marker === "CIRCULAR" ? "" : "any";
  return node.nullable ? (base ? `${base} | null` : "null") : base;
}

/** The refs the reader can jump to: `#/components/schemas/Pet` → the Schemas section's anchor. */
function refAnchor(ref: string, schemaAnchors: ReadonlyMap<string, string>): string | undefined {
  return schemaAnchors.get(ref);
}

function SchemaFacts({ node }: { node: SchemaNode }) {
  const { t } = useTranslation();
  const flags = [
    node.deprecated && t("reader.schema.deprecated"),
    node.readOnly && t("reader.schema.readOnly"),
    node.writeOnly && t("reader.schema.writeOnly"),
  ].filter((f): f is string => typeof f === "string");
  const shownEnum = node.enumValues.slice(0, MAX_ENUM_CHIPS);
  return (
    <Stack gap={4}>
      {node.description && (
        <Text size="sm" c="dimmed" style={{ whiteSpace: "pre-wrap" }}>
          {node.description}
        </Text>
      )}
      {(flags.length > 0 || node.constraints.length > 0) && (
        <Group gap={4} wrap="wrap">
          {flags.map((f) => (
            <Badge key={f} color="orange" variant="light" size="xs">
              {f}
            </Badge>
          ))}
          {node.constraints.map((c) => (
            <Badge key={c.keyword} color="gray" variant="light" size="xs" style={{ textTransform: "none" }}>
              {c.keyword}: {c.value}
            </Badge>
          ))}
        </Group>
      )}
      {node.enumValues.length > 0 && (
        <Group gap={4} wrap="wrap">
          <Text size="xs" c="dimmed">
            {t("reader.schema.enum")}:
          </Text>
          {shownEnum.map((v) => (
            <Code key={v} fz="xs">
              {v}
            </Code>
          ))}
          {node.enumValues.length > MAX_ENUM_CHIPS && (
            <Text size="xs" c="dimmed">
              {t("reader.schema.more", { count: node.enumValues.length - MAX_ENUM_CHIPS })}
            </Text>
          )}
        </Group>
      )}
      {node.constValue != null && (
        <Text size="xs" c="dimmed">
          {t("reader.schema.const")}: <Code fz="xs">{node.constValue}</Code>
        </Text>
      )}
      {node.defaultValue != null && (
        <Text size="xs" c="dimmed">
          {t("reader.schema.default")}: <Code fz="xs">{node.defaultValue}</Code>
        </Text>
      )}
      {node.examples.length > 0 && (
        <Text size="xs" c="dimmed">
          {t("reader.schema.examples")}:{" "}
          {node.examples.map((e, i) => (
            <Code key={i} fz="xs" mr={4}>
              {e}
            </Code>
          ))}
        </Text>
      )}
      {node.discriminator && (
        <Text size="xs" c="dimmed">
          {t("reader.schema.discriminator", { property: node.discriminator.propertyName })}
        </Text>
      )}
    </Stack>
  );
}

function NodeRow({
  node,
  label,
  required,
  depth,
  schemaAnchors,
}: {
  node: SchemaNode;
  label?: string;
  required?: boolean;
  depth: number;
  schemaAnchors: ReadonlyMap<string, string>;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(depth < OPEN_DEPTH);
  const childrenId = useId();
  const expandable = hasChildren(node) && node.marker == null;
  const anchor = node.ref ? refAnchor(node.ref, schemaAnchors) : undefined;
  const refName = node.ref ? node.ref.split("/").pop() ?? node.ref : undefined;
  return (
    <Box data-pointer={node.pointer} pl={depth === 0 ? 0 : "md"} style={depth === 0 ? undefined : { borderLeft: "1px solid var(--mantine-color-default-border)" }}>
      <Group gap={6} wrap="nowrap" align="flex-start">
        {expandable ? (
          <ActionIcon
            size="xs"
            variant="subtle"
            color="gray"
            onClick={() => setOpen((o) => !o)}
            aria-expanded={open}
            aria-controls={childrenId}
            aria-label={open ? t("reader.schema.collapse", { name: label ?? node.title ?? "" }) : t("reader.schema.expand", { name: label ?? node.title ?? "" })}
          >
            {open ? <IconChevronDown size={12} /> : <IconChevronRight size={12} />}
          </ActionIcon>
        ) : (
          <Box w={18} style={{ flexShrink: 0 }} />
        )}
        <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
          <Group gap={6} wrap="wrap" align="baseline">
            {label != null && (
              <Text size="sm" ff="monospace" fw={500}>
                {label}
                {required && (
                  <Text span c="red" aria-label={t("reader.schema.required")} title={t("reader.schema.required")}>
                    {" *"}
                  </Text>
                )}
              </Text>
            )}
            {typeLabel(node) && (
              <Text size="sm" c="dimmed">
                {typeLabel(node)}
              </Text>
            )}
            {node.format && (
              <Text size="xs" c="dimmed">
                ({node.format})
              </Text>
            )}
            {node.title && label !== node.title && depth > 0 && !node.ref && (
              <Text size="xs" c="dimmed">
                {node.title}
              </Text>
            )}
            {refName && node.marker !== "UNRESOLVED" && (
              anchor ? (
                <Anchor href={`#${anchor}`} size="xs">
                  {refName}
                </Anchor>
              ) : (
                <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
                  {refName}
                </Badge>
              )
            )}
            {node.marker === "CIRCULAR" && (
              <Badge variant="light" color="gray" size="xs" style={{ textTransform: "none" }}>
                {t("reader.schema.recursive", { name: node.title ?? refName ?? "" })}
              </Badge>
            )}
            {node.marker === "UNRESOLVED" && (
              <Badge variant="light" color="orange" size="xs" style={{ textTransform: "none" }}>
                {t("reader.schema.unresolved", { ref: node.ref ?? "" })}
              </Badge>
            )}
            {node.marker === "TRUNCATED" && (
              <Badge variant="light" color="gray" size="xs">
                {t("reader.schema.truncated")}
              </Badge>
            )}
            {node.raw != null && (
              <Badge variant="light" color="gray" size="xs">
                {t("reader.schema.raw")}
              </Badge>
            )}
          </Group>
          <SchemaFacts node={node} />
          {node.raw != null && (
            <Code block fz="xs" style={{ whiteSpace: "pre-wrap" }}>
              {node.raw}
            </Code>
          )}
          {expandable && open && (
            <Stack gap={4} id={childrenId} mt={4}>
              {node.properties.map((p) => (
                <NodeRow key={p.name} node={p.schema} label={p.name} required={p.required} depth={depth + 1} schemaAnchors={schemaAnchors} />
              ))}
              {node.items && <NodeRow node={node.items} label={t("reader.schema.items")} depth={depth + 1} schemaAnchors={schemaAnchors} />}
              {node.tupleItems.map((s, i) => (
                <NodeRow key={i} node={s} label={t("reader.schema.item", { index: i })} depth={depth + 1} schemaAnchors={schemaAnchors} />
              ))}
              {node.additionalProperties && (
                <NodeRow node={node.additionalProperties} label={t("reader.schema.additionalProperties")} depth={depth + 1} schemaAnchors={schemaAnchors} />
              )}
              {(["allOf", "anyOf", "oneOf"] as const).map((group) =>
                node[group].map((s, i) => <NodeRow key={`${group}-${i}`} node={s} label={`${group}[${i}]`} depth={depth + 1} schemaAnchors={schemaAnchors} />),
              )}
              {node.not && <NodeRow node={node.not} label="not" depth={depth + 1} schemaAnchors={schemaAnchors} />}
            </Stack>
          )}
          {node.additionalPropertiesAllowed === false && node.properties.length > 0 && (
            <Text size="xs" c="dimmed">
              {t("reader.schema.noAdditional")}
            </Text>
          )}
        </Stack>
      </Group>
    </Box>
  );
}

/**
 * The recursive schema view shared by every reader: name, `type | null`, dimmed format, a `*` for
 * required, ref chips linking into the Schemas section, the CIRCULAR / UNRESOLVED / TRUNCATED
 * markers, the facts (constraints, enum, default, examples), and the children under expanders —
 * open at the top level, collapsed deeper down.
 */
export default function SchemaTree({ node, schemaAnchors = new Map() }: { node: SchemaNode; schemaAnchors?: ReadonlyMap<string, string> }) {
  return <NodeRow node={node} depth={0} schemaAnchors={schemaAnchors} />;
}
