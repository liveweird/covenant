import { readFile } from "node:fs/promises";
import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, APIResponse, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, type SeededContract, uniqueText } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

test.describe.serial("release-line lifecycle plans", () => {
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let api: APIRequestContext;
  let seeded: SeededContract;
  let connectionId = 0;
  const connectionName = uniqueText("e2e-lifecycle-toadie");
  const versionsPath = () => `/api/v1/contracts/${seeded.contractId}/versions`;
  const line = (page: Page, major: number) => page.getByRole("region", { name: `Release line ${major}.x`, exact: true });

  async function ok(response: APIResponse, status: number) {
    expect(response.status(), await response.text()).toBe(status);
  }

  async function transition(versionId: number, to: string) {
    await ok(await api.post(`${versionsPath()}/${versionId}/transition`, { data: { to } }), 200);
  }

  test.beforeAll(async () => {
    upstream = await startToadieFixture();
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-lifecycle", PETSTORE("Lifecycle plan", "1.0.0"));
    await ok(await api.post(versionsPath(), { data: { version: "2.0.0", content: PETSTORE(seeded.contractName, "2.0.0") } }), 201);
    await transition(seeded.versionId, "PROPOSED");
    await transition(seeded.versionId, "ACTIVE");
    await transition(seeded.versionId, "DEPRECATED");

    const connection = await api.post("/api/v1/toadie-connections", { data: {
      name: connectionName,
      baseUrl: upstream.baseUrl,
      browserUrl: upstream.browserUrl,
      apiKey: upstream.key,
      enabled: true,
      refreshIntervalMinutes: 60,
      mapping: {
        serviceBlueprint: "service",
        apiBlueprint: "api",
        providesRelation: "provides_apis",
        consumesRelation: "consumes_apis",
        systemRelation: "system",
      },
    } });
    await ok(connection, 201);
    connectionId = ((await connection.json()) as { id: number }).id;
    await expect.poll(async () => {
      const response = await api.get(`/api/v1/toadie-connections/${connectionId}`);
      const state = await response.json() as { lastSuccessAt: number | null; lastErrorCode: string | null };
      return state.lastErrorCode ?? (state.lastSuccessAt == null ? "pending" : "ready");
    }, { timeout: 15_000 }).toBe("ready");
    await ok(await api.put(`/api/v1/contracts/${seeded.contractId}/toadie-links`, {
      data: { connectionId, apiEntityIds: ["1", "2"] },
    }), 204);
  });

  test.afterAll(async () => {
    try {
      if (api && seeded) {
        const response = await api.get(`${versionsPath()}?pageSize=100`);
        if (response.ok()) {
          const { items } = await response.json() as { items: { id: number; lifecycle: string }[] };
          for (const version of items) {
            if (version.lifecycle === "ACTIVE") await transition(version.id, "DEPRECATED");
            if (version.lifecycle === "ACTIVE" || version.lifecycle === "DEPRECATED") await transition(version.id, "RETIRED");
          }
        }
        await teardownSeededContract(api, seeded);
      }
      if (api && connectionId) await ok(await api.delete(`/api/v1/toadie-connections/${connectionId}`), 204);
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("an owner records and reloads a lifecycle plan with a replacement release line", async ({ page }) => {
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    await page.getByRole("button", { name: "Edit policy for 1.x", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Edit policy for 1.x", exact: true });
    await dialog.getByLabel("Deprecates on", { exact: true }).fill("2030-06-01");
    await dialog.getByLabel("Support ends on", { exact: true }).fill("2030-12-31");
    await dialog.getByLabel("Migration instructions", { exact: true }).fill("Move consumers to the 2.x API before support ends.");
    const replacement = dialog.getByRole("combobox", { name: "Replacement contract", exact: true });
    await replacement.click();
    await replacement.fill(seeded.contractName);
    await page.getByRole("option").filter({ hasText: seeded.contractName }).click();
    await dialog.getByRole("combobox", { name: "Replacement release line", exact: true }).click();
    await page.getByRole("option", { name: "2.x", exact: true }).click();
    await dialog.getByRole("button", { name: "Save policy", exact: true }).click();
    await expect(dialog).toHaveCount(0);

    await page.reload();
    await expect(line(page, 1)).toContainText("Planned deprecation: 2030-06-01");
    await expect(line(page, 1)).toContainText("2030-12-31");
    await expect(line(page, 1)).toContainText("Move consumers to the 2.x API before support ends.");
    await expect(line(page, 1)).toContainText(`${seeded.contractName} · 2.x`);
    const version = await (await api.get(`${versionsPath()}/${seeded.versionId}`)).json() as { lifecycle: string };
    expect(version.lifecycle).toBe("DEPRECATED");
  });

  test("a migration report includes the saved plan and all declared usage without changing lifecycle", async ({ page }, testInfo) => {
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    await line(page, 1).getByRole("button", { name: "Review retirement impact for 1.x", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Retirement impact for 1.x", exact: true });
    await expect(dialog).toContainText("Storefront website");
    // Search only the visible consumer table; the report must still include every declared role.
    await dialog.getByRole("textbox", { name: "Service", exact: true }).fill("no-visible-services");
    await expect(dialog.getByText("Storefront website", { exact: true })).toHaveCount(0);
    const downloaded = page.waitForEvent("download");
    await dialog.getByRole("button", { name: "Download report", exact: true }).click();
    const download = await downloaded;
    expect(download.suggestedFilename()).toMatch(/\.md$/);
    const file = await download.path();
    expect(file).not.toBeNull();
    const report = await readFile(file!, "utf8");
    const plainText = report.replace(/\\([\\`*_{}[\]()<>#+\-.!|])/g, "$1");
    for (const expected of [seeded.contractName, "1.x", "2.x", "2030-06-01", "2030-12-31",
      "Move consumers to the 2.x API before support ends.", "Checkout service", "Storefront website",
      "Commerce system", "Retail team", "Orders API", "Order events"]) expect(plainText).toContain(expected);
    expect(report).toMatch(/whole contract/i);
    expect(report).toMatch(/unknown/i);
    expect(report).not.toContain(upstream.key);
    await testInfo.attach("Migration and impact report", { body: report, contentType: "text/markdown" });
    const scan = await new AxeBuilder({ page }).include('[role="dialog"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Report download action", { body: await dialog.screenshot(), contentType: "image/png" });
    await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
    expect(((await (await api.get(`${versionsPath()}/${seeded.versionId}`)).json()) as { lifecycle: string }).lifecycle).toBe("DEPRECATED");
  });

  test("retirement reviews declared consumers and requires explicit acknowledgement", async ({ page }, testInfo) => {
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}/versions/${seeded.versionId}`);
    await page.getByRole("button", { name: "Retire", exact: true }).click();
    let dialog = page.getByRole("dialog", { name: "Retirement impact for 1.x", exact: true });
    await expect(dialog).toContainText("Storefront website");
    await expect(dialog).toContainText("Commerce system");
    await expect(dialog).toContainText("Retail team");
    await expect(dialog).toContainText(/whole contract.*runtime adoption remains unknown/);
    await expect(dialog.getByText("Unknown", { exact: true })).toHaveCount(2);
    await expect(dialog.getByRole("button", { name: "Retire", exact: true })).toBeDisabled();
    const scan = await new AxeBuilder({ page }).include('[role="dialog"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Retirement impact with declared consumers", {
      body: await dialog.screenshot(),
      contentType: "image/png",
    });
    await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    expect(((await (await api.get(`${versionsPath()}/${seeded.versionId}`)).json()) as { lifecycle: string }).lifecycle).toBe("DEPRECATED");

    await page.getByRole("button", { name: "Retire", exact: true }).click();
    dialog = page.getByRole("dialog", { name: "Retirement impact for 1.x", exact: true });
    await dialog.getByRole("checkbox", { name: "I have reviewed the declared usage and its limitations and choose to proceed." }).check();
    await expect(dialog.getByRole("button", { name: "Retire", exact: true })).toBeEnabled();
    await dialog.getByRole("button", { name: "Retire", exact: true }).click();
    await expect(page.getByRole("button", { name: "Retire", exact: true })).toHaveCount(0);
    expect(((await (await api.get(`${versionsPath()}/${seeded.versionId}`)).json()) as { lifecycle: string }).lifecycle).toBe("RETIRED");
  });
});
