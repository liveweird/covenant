import type { TFunction } from "i18next";
import type { ReleaseLineMigrationReportResponse } from "../api/releaseLines";

/** Escape every Markdown structural character while retaining the supplied plain text and line breaks. */
export function markdownText(value: string): string {
  return value.replace(/([\\`*_{}[\]()<>#+\-.!|&~])/g, "\\$1");
}

function inlineCode(value: string): string {
  const longestRun = Math.max(0, ...[...value.matchAll(/`+/g)].map((match) => match[0].length));
  const fence = "`".repeat(Math.max(1, longestRun + 1));
  return `${fence} ${value} ${fence}`;
}

function textBlock(value: string | null, t: TFunction): string {
  const text = value == null || value === "" ? t("contracts.releaseLines.report.notProvided") : value;
  const longestRun = Math.max(0, ...[...text.matchAll(/`+/g)].map((match) => match[0].length));
  const fence = "`".repeat(Math.max(3, longestRun + 1));
  return `${fence}text\n${text}\n${fence}`;
}

function value(text: string | null, t: TFunction): string {
  return text == null || text === "" ? t("contracts.releaseLines.report.notProvided") : markdownText(text);
}

function utc(epochMillis: number | null, t: TFunction): string {
  return epochMillis == null ? t("contracts.releaseLines.report.notAvailable") : new Date(epochMillis).toISOString();
}

function safeUrl(raw: string | null | undefined): string | null {
  if (!raw) return null;
  try {
    const url = new URL(raw);
    if ((url.protocol !== "https:" && url.protocol !== "http:") || url.username || url.password) return null;
    return url.toString();
  } catch {
    return null;
  }
}

function linkedLabel(label: string, rawUrl: string | null | undefined): string {
  const url = safeUrl(rawUrl);
  return url ? `[${markdownText(label)}](<${url.replace(/&/g, "&amp;").replace(/>/g, "%3E")}>)` : inlineCode(label);
}

function list(values: string[], t: TFunction): string {
  return values.length ? values.join(", ") : t("contracts.releaseLines.report.none");
}

function cacheStateKey(state: ReleaseLineMigrationReportResponse["cache"]["state"]): "current" | "stale" | "disabled" | "disconnected" | "neverSynced" | "unlinked" {
  if (state === "NEVER_SYNCED") return "neverSynced";
  return state.toLowerCase() as "current" | "stale" | "disabled" | "disconnected" | "unlinked";
}

export function migrationReportMarkdown(report: ReleaseLineMigrationReportResponse, t: TFunction): string {
  const status = t(`contracts.releaseLines.status.${report.supportStatus}`);
  const cacheState = cacheStateKey(report.cache.state);
  const replacement = report.replacement == null
    ? t("contracts.releaseLines.report.none")
    : report.replacement.available
      ? `${inlineCode(report.replacement.contractName ?? `#${report.replacement.contractId}`)} (#${report.replacement.contractId}${report.replacement.major == null ? "" : `, ${report.replacement.major}.x`})`
      : t("contracts.releaseLines.replacementUnavailable", { id: report.replacement.contractId, line: report.replacement.major == null ? "" : ` · ${report.replacement.major}.x` });
  const recommendation = report.recommendedVersion == null
    ? t("contracts.releaseLines.noRecommendedVersion")
    : `${markdownText(report.recommendedVersion.version)} (#${report.recommendedVersion.id}, ${t(`versions.lifecycle.${report.recommendedVersion.lifecycle}`)})`;
  const connection = report.connection == null
    ? t("contracts.releaseLines.report.none")
    : `${linkedLabel(report.connection.name, report.connection.browserUrl)} (#${report.connection.id})`;
  const links = report.linkedApis.length
    ? report.linkedApis.map((link) => `- ${linkedLabel(link.title, link.url)} — ${t("contracts.releaseLines.report.identifier")}: ${inlineCode(link.identifier)}; ${t("contracts.releaseLines.report.apiId")}: ${inlineCode(link.apiEntityId)}; ${t("contracts.releaseLines.report.mappingStatus")}: ${t(`contracts.releaseLines.report.linkStatus.${link.status}`)}`)
    : [`- ${t("contracts.releaseLines.report.none")}`];
  const usageIncomplete = report.cache.state !== "CURRENT" || report.cache.refreshing || report.cache.lastErrorCode != null || report.linkedApis.some((link) => link.status !== "AVAILABLE");
  const usageWarning = usageIncomplete ? `\n>\n> ${t("contracts.releaseLines.report.incompleteWarning")}` : "";
  const serviceLines = report.services.length
    ? report.services.map((service) => {
      const roles = service.roles.map((role) => t(`contracts.releaseLines.report.role.${role}`));
      const systems = service.systems.map((entry) => `${linkedLabel(entry.title, entry.url)} (${t("contracts.releaseLines.report.identifier")}: ${inlineCode(entry.identifier)}; ${t("contracts.releaseLines.report.entityId")}: ${inlineCode(entry.entityId)})`);
      const teams = service.teams.map((entry) => `${linkedLabel(entry.title, entry.url)} (${t("contracts.releaseLines.report.identifier")}: ${inlineCode(entry.identifier)}; ${t("contracts.releaseLines.report.entityId")}: ${inlineCode(entry.entityId)})`);
      return `### ${linkedLabel(service.title, service.url)}\n\n- ${t("contracts.releaseLines.report.identifier")}: ${inlineCode(service.identifier)}\n- ${t("contracts.releaseLines.report.serviceId")}: ${inlineCode(service.id)}\n- ${t("contracts.releaseLines.report.roles")}: ${list(roles, t)}\n- ${t("contracts.releaseLines.report.providedApiIds")}: ${list(service.providedApiEntityIds.map(inlineCode), t)}\n- ${t("contracts.releaseLines.report.consumedApiIds")}: ${list(service.consumedApiEntityIds.map(inlineCode), t)}\n- ${t("contracts.releaseLines.report.systems")}: ${list(systems, t)}\n- ${t("contracts.releaseLines.report.teams")}: ${list(teams, t)}\n- ${t("contracts.releaseLines.report.versionAdoption")}: ${t("contracts.releaseLines.report.unknown")}`;
    })
    : [usageIncomplete ? t("contracts.releaseLines.report.usageUnavailable") : t("contracts.releaseLines.report.noObservedUsage")];

  return `# ${t("contracts.releaseLines.report.title")}\n\n## ${t("contracts.releaseLines.report.contract")}\n\n- ${t("contracts.releaseLines.report.name")}: ${inlineCode(report.contractName)}\n- ${t("contracts.releaseLines.report.contractId")}: ${report.contractId}\n- ${t("contracts.releaseLines.report.type")}: ${report.contractType}\n- ${t("contracts.releaseLines.report.releaseLine")}: ${report.major}.x\n- ${t("contracts.releaseLines.report.generatedAt")}: ${utc(report.generatedAt, t)}\n- ${t("contracts.releaseLines.report.planUpdatedAt")}: ${utc(report.planUpdatedAt, t)}\n\n## ${t("contracts.releaseLines.report.plan")}\n\n- ${t("contracts.releaseLines.supportStatus")}: ${status}\n- ${t("contracts.releaseLines.deprecatesOn")}: ${value(report.deprecatesOn, t)}\n- ${t("contracts.releaseLines.supportEndsOn")}: ${value(report.supportEndsOn, t)}\n- ${t("contracts.releaseLines.recommendedVersion")}: ${recommendation}\n- ${t("contracts.releaseLines.report.replacement")}: ${replacement}\n\n### ${t("contracts.releaseLines.supportPolicy")}\n\n${textBlock(report.supportPolicy, t)}\n\n### ${t("contracts.releaseLines.migrationGuide")}\n\n${textBlock(report.migrationGuide, t)}\n\n## ${t("contracts.releaseLines.report.usage")}\n\n> ${t("contracts.releaseLines.report.scopeWarning")}\n>\n> ${t("contracts.releaseLines.report.emptyWarning")}${usageWarning}\n\n- ${t("contracts.releaseLines.report.scope")}: ${t("contracts.releaseLines.report.contractScope")}\n- ${t("contracts.releaseLines.report.versionAdoption")}: ${t("contracts.releaseLines.report.unknown")}\n- ${t("toadie.usage.connection")}: ${connection}\n- ${t("contracts.releaseLines.report.cacheState")}: ${t(`toadie.status.${cacheState}`)}\n- ${t("contracts.releaseLines.report.refreshing")}: ${report.cache.refreshing ? t("contracts.releaseLines.report.yes") : t("contracts.releaseLines.report.no")}\n- ${t("contracts.releaseLines.report.lastSuccessAt")}: ${utc(report.cache.lastSuccessAt, t)}\n- ${t("contracts.releaseLines.report.lastAttemptAt")}: ${utc(report.cache.lastAttemptAt, t)}\n- ${t("contracts.releaseLines.report.lastError")}: ${value(report.cache.lastErrorCode, t)}\n\n## ${t("contracts.releaseLines.report.linkedApis")}\n\n${links.join("\n")}\n\n## ${t("contracts.releaseLines.report.services")}\n\n${serviceLines.join("\n\n")}\n`;
}

export function migrationReportFileName(report: Pick<ReleaseLineMigrationReportResponse, "contractId" | "major">): string {
  return `contract-${report.contractId}__release-line-${report.major}__migration-report.md`;
}
