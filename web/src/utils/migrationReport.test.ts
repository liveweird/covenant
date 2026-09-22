import { describe, expect, test } from "vitest";
import { renderToStaticMarkup } from "react-dom/server";
import { createElement } from "react";
import { MantineProvider } from "@mantine/core";
import i18n from "../i18n";
import type { ReleaseLineMigrationReportResponse } from "../api/releaseLines";
import MarkdownView from "../components/MarkdownView";
import { markdownText, migrationReportFileName, migrationReportMarkdown } from "./migrationReport";

function report(overrides: Partial<ReleaseLineMigrationReportResponse> = {}): ReleaseLineMigrationReportResponse {
  return {
    generatedAt: Date.UTC(2026, 8, 21, 10, 11, 12), contractId: 5, contractName: "Orders", contractType: "OPENAPI", major: 1,
    supportStatus: "MAINTENANCE", deprecatesOn: "2027-06-01", supportEndsOn: "2027-12-31", supportPolicy: "Security fixes only",
    migrationGuide: "Move to v2.\nThen remove v1.", replacement: { contractId: 9, contractName: "New orders", major: 2, available: true },
    recommendedVersion: { id: 31, version: "1.8.0", lifecycle: "ACTIVE" }, planUpdatedAt: Date.UTC(2026, 8, 20, 9),
    usageScope: "CONTRACT", versionAdoption: "UNKNOWN", connection: { id: 2, name: "Toadie", browserUrl: "https://toadie.example/" },
    cache: { state: "CURRENT", lastAttemptAt: Date.UTC(2026, 8, 21, 8), lastSuccessAt: Date.UTC(2026, 8, 21, 7), refreshing: false, lastErrorCode: null },
    linkedApis: [{ id: 4, connectionId: 2, apiEntityId: "api-orders", identifier: "orders", title: "Orders API", url: "https://toadie.example/apis/orders", status: "AVAILABLE" }],
    services: [], adoptions: { availability: "NOT_CONFIGURED", items: [] }, ...overrides,
  };
}

describe("migration report Markdown", () => {
  test("exports the complete fresh snapshot, including more than one displayed page of services", async () => {
    await i18n.changeLanguage("en");
    const services = Array.from({ length: 101 }, (_, index) => ({
      id: `service-${index}`, identifier: `svc-${index}`, title: `Service ${index}`, url: null,
      roles: index === 100 ? ["PROVIDER", "CONSUMER"] : ["CONSUMER"],
      providedApiEntityIds: index === 100 ? ["api-provided"] : [], consumedApiEntityIds: ["api-orders"],
      systems: [{ entityId: `system-${index}`, identifier: `sys-${index}`, title: `System ${index}`, url: null }],
      teams: [{ entityId: `team-${index}`, identifier: `team-${index}`, title: `Team ${index}`, url: null }],
      version: null, releaseLine: null,
    })) as ReleaseLineMigrationReportResponse["services"];
    const markdown = migrationReportMarkdown(report({ services }), i18n.t);
    expect(markdown).toContain("Service 0");
    expect(markdown).toContain("Service 100");
    expect(markdown).toContain("Roles: Provider, Consumer");
    expect(markdown).toContain("Provided entity IDs: ` api-provided `");
    expect(markdown).toContain("Consumed entity IDs: ` api-orders `");
    expect(markdown).toContain("Identifier: ` sys-100 `; Entity ID: ` system-100 `");
    expect(markdown).toContain("Identifier: ` team-100 `; Entity ID: ` team-100 `");
    expect(markdown).toContain("Report generated at (UTC): 2026-09-21T10:11:12.000Z");
    expect(markdown).toContain("Plan updated at (UTC): 2026-09-20T09:00:00.000Z");
    expect(markdown).toContain("Last successful refresh at (UTC): 2026-09-21T07:00:00.000Z");
    expect(markdown).toContain("Last refresh attempt at (UTC): 2026-09-21T08:00:00.000Z");
    expect(migrationReportFileName(report())).toBe("contract-5__release-line-1__migration-report.md");
  });

  test("localizes Polish report labels and caveats", async () => {
    await i18n.changeLanguage("pl");
    const markdown = migrationReportMarkdown(report(), i18n.t);
    expect(markdown).toContain("# Raport migracji i skutków wycofania");
    expect(markdown).toContain("Wersja i główna linia używana w działających systemach pozostają nieznane");
    expect(markdown).toContain("Nie dowodzi to, że kontrakt jest nieużywany");
    await i18n.changeLanguage("en");
  });

  test("distinguishes current empty usage from stale or missing observations", async () => {
    await i18n.changeLanguage("en");
    const current = migrationReportMarkdown(report(), i18n.t);
    expect(current).toContain("No declared usage was observed in this snapshot");
    expect(current).not.toContain("Unknown usage is not zero usage");
    const stale = migrationReportMarkdown(report({ cache: { state: "STALE", lastAttemptAt: 3, lastSuccessAt: 2, refreshing: false, lastErrorCode: "UPSTREAM" } }), i18n.t);
    expect(stale).toContain("Unknown usage is not zero usage");
    expect(stale).toContain("Usage is incomplete, stale, refreshing, or unavailable");
    const missing = migrationReportMarkdown(report({ linkedApis: [{ ...report().linkedApis[0], status: "MISSING" }] }), i18n.t);
    expect(missing).toContain("Mapping status: Missing");
    expect(missing).toContain("Unknown usage is not zero usage");
  });

  test("escapes hostile remote text and drops unsafe URLs", async () => {
    await i18n.changeLanguage("en");
    const markdown = migrationReportMarkdown(report({
      contractName: "# False heading <script>alert(1)</script>",
      supportPolicy: "Use &copy; and ~~deprecated~~ literally.",
      migrationGuide: "[official](https://evil.example)\n# Retire immediately\n```nested```",
      connection: { id: 2, name: "Toadie", browserUrl: "https://toadie.example/&copy;" },
      services: [{ id: "x", identifier: "bad|id", title: "[Trusted](https://evil.example)", url: "javascript:alert(1)", roles: ["CONSUMER"], providedApiEntityIds: [], consumedApiEntityIds: [], systems: [], teams: [], version: null, releaseLine: null }],
      adoptions: { availability: "AVAILABLE", items: [{
        id: "adopt-1", identifier: "orders|prod", title: "Orders <declaration>", url: "javascript:alert(2)",
        consumer: { entityId: "service-1", identifier: "checkout", title: "Checkout", url: null },
        target: { entityId: "api-1", identifier: "orders", title: "Orders API", url: "https://toadie.example/apis/orders" },
        environment: null, environmentScope: "ALL", kind: "API_MAJOR_LINE", value: "1.x `candidate`\n\n<img src=x onerror=alert(3)>", status: "current|stable\n\n[bad](javascript:alert(4))",
        declaredBy: "portfolio/[owner]\n\n# forged heading", verifiedAt: Date.UTC(2026, 8, 20, 8), notes: "Keep `orders` compatible <script>not executable</script>.", matchesConsumption: false,
      }] },
    }), i18n.t);
    const html = renderToStaticMarkup(createElement(MantineProvider, { env: "test" }, createElement(MarkdownView, null, markdown)));
    expect(html).toContain("# False heading &lt;script&gt;alert(1)&lt;/script&gt;");
    expect(html).toContain("[official](https://evil.example)");
    expect(html).toContain("# Retire immediately");
    expect(html).toContain("Use &amp;copy; and ~~deprecated~~ literally.");
    expect(html).not.toContain("<del>");
    expect(html).not.toContain("<script>");
    expect(html).toContain('href="https://toadie.example/&amp;copy;"');
    expect(markdown).not.toContain("javascript:alert(1)");
    expect(markdown).not.toContain("javascript:alert(2)");
    expect(markdown).toContain("Orders <declaration>");
    expect(markdown).toContain("Verified upstream at (UTC): 2026-09-20T08:00:00.000Z");
    expect(markdown).toContain("```text\nKeep `orders` compatible <script>not executable</script>.\n```");
    expect(html).toContain("&lt;script&gt;not executable&lt;/script&gt;");
    expect(html).not.toContain("<img");
    expect(html).not.toContain("href=\"javascript:");
    expect(markdown).toContain("\\<img src=x onerror=alert\\(3\\)\\>");
    expect(markdown).toContain("\\# forged heading");
    expect(markdownText("<h1>&copy; ~~x~~</h1>")).toBe("\\<h1\\>\\&copy; \\~\\~x\\~\\~\\</h1\\>");
  });
});
