import type { NamedSchemaView } from "../api/versions";

/** The anchor id of the i-th named schema in the reader's Schemas section — the ref chips in every tree point here. */
export function schemaAnchorId(index: number): string {
  return `schema-${index}`;
}

/** `#/components/schemas/<name>` → its anchor, for every named schema. */
export function schemaAnchors(schemas: readonly NamedSchemaView[]): Map<string, string> {
  return new Map(schemas.map((s, i) => [`#${s.pointer}`, schemaAnchorId(i)]));
}
