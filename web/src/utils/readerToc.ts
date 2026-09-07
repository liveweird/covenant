/**
 * The reader's tables of contents, pure over the render model, kept out of the
 * components so the version page's side panel can build the TOC without importing
 * the lazy reader chunk.
 */

import type { TFunction } from "i18next";
import type { RenderModel, OpenApiModel, AsyncApiModel, OdcsModel, OperationView } from "../api/versions";

export type TocEntry = { id: string; label: string; children?: TocEntry[] };

const UNTAGGED = "__untagged";

/** Operations grouped by their first tag (declared order, undeclared appended by the server), untagged ones last. */
export function groupByTag(model: OpenApiModel): { key: string; label: string | null; operations: OperationView[] }[] {
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

export const opId = (model: OpenApiModel, op: OperationView) => `op-${model.operations.indexOf(op)}`;

/** The version page's side panel table of contents for the OpenAPI reader: tags, webhooks, schemas, security. */
function openApiToc(model: OpenApiModel, t: TFunction): TocEntry[] {
  const groups = groupByTag(model);
  return [
    ...groups.map((g) => ({
      id: `tag-${g.key}`,
      label: g.label ?? t("reader.untagged"),
      children: g.operations.map((op) => ({ id: opId(model, op), label: `${op.method} ${op.path}` })),
    })),
    ...(model.webhooks.length > 0 ? [{ id: "reader-webhooks", label: t("reader.section.webhooks") }] : []),
    ...(model.schemas.length > 0 ? [{ id: "reader-schemas", label: t("reader.section.schemas") }] : []),
    ...(model.securitySchemes.length > 0 ? [{ id: "reader-security", label: t("reader.section.security") }] : []),
  ];
}

/** The version page's side panel table of contents for the AsyncAPI reader: servers, channels, operations, messages, schemas, security. */
function asyncApiToc(model: AsyncApiModel, t: TFunction): TocEntry[] {
  return [
    ...(model.servers.length > 0 ? [{ id: "reader-servers", label: t("reader.section.servers") }] : []),
    { id: "reader-channels", label: t("reader.section.channels"), children: model.channels.map((c, i) => ({ id: `channel-${i}`, label: c.name })) },
    { id: "reader-operations", label: t("reader.section.operations"), children: model.operations.map((o, i) => ({ id: `operation-${i}`, label: o.name })) },
    { id: "reader-messages", label: t("reader.section.messages"), children: model.messages.map((m, i) => ({ id: `message-${i}`, label: m.name ?? m.key })) },
    ...(model.schemas.length > 0 ? [{ id: "reader-schemas", label: t("reader.section.schemas") }] : []),
    ...(model.securitySchemes.length > 0 ? [{ id: "reader-security", label: t("reader.section.security") }] : []),
  ];
}

/** The version page's side panel table of contents for the ODCS reader: description, datasets, servers, team, roles, SLA, support, price, custom properties. */
export function odcsToc(model: OdcsModel, t: TFunction): TocEntry[] {
  return [
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
}

/**
 * The version page's side panel table of contents, mirroring the reader's family dispatch —
 * empty for an unparseable document or one none of the three families claim.
 */
export function readerToc(model: RenderModel, t: TFunction): TocEntry[] {
  if (model.error) return [];
  if (model.openApi) return openApiToc(model.openApi, t);
  if (model.asyncApi) return asyncApiToc(model.asyncApi, t);
  if (model.odcs) return odcsToc(model.odcs, t);
  return [];
}
