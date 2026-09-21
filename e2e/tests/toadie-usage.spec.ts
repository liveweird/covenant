import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

async function waitForRefreshCooldown(api: APIRequestContext, connectionId: number) {
  const state = await (await api.get(`/api/v1/toadie-connections/${connectionId}`)).json() as { lastAttemptAt: number };
  // Exercise the public manual-refresh path without bypassing its real cooldown.
  await expect.poll(() => Date.now() - state.lastAttemptAt, { timeout: 35_000, intervals: [1000] }).toBeGreaterThan(31_000);
}

test.describe.serial("Toadie contract usage", () => {
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let api: APIRequestContext;
  let seeded: SeededContract;
  let connectionId: number;
  const connectionName = uniqueText("e2e-toadie");

  test.beforeAll(async () => {
    upstream = await startToadieFixture();
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-toadie", PETSTORE("Toadie usage", "1.0.0"));
  });

  test.afterAll(async () => {
    try {
      if (api) {
        if (seeded) await teardownSeededContract(api, seeded);
        if (connectionId) {
          const response = await api.delete(`/api/v1/toadie-connections/${connectionId}`);
          expect(response.status(), await response.text()).toBe(204);
        }
      }
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("an administrator connects Toadie and a contract owner links multiple APIs to view declared usage", async ({ page }, testInfo) => {
    await login(page);
    await page.goto("/toadie-connections");
    await page.getByRole("button", { name: "New connection", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "New Toadie connection", exact: true });
    await dialog.getByRole("textbox", { name: "Name", exact: true }).fill(connectionName);
    await dialog.getByRole("textbox", { name: "Backend base URL", exact: true }).fill(upstream.baseUrl);
    await dialog.getByRole("textbox", { name: "Browser URL", exact: true }).fill(upstream.browserUrl);
    await dialog.getByRole("textbox", { name: "API key", exact: true }).fill(upstream.key);
    const [created] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith("/api/v1/toadie-connections") && response.request().method() === "POST"),
      dialog.getByRole("button", { name: "Create", exact: true }).click(),
    ]);
    expect(created.status(), await created.text()).toBe(201);
    const connection = await created.json() as { id: number; hasApiKey: boolean; apiKey?: string };
    connectionId = connection.id;
    expect(connection.hasApiKey).toBe(true);
    expect(connection.apiKey).toBeUndefined();
    await expect(dialog).toHaveCount(0);
    await expect.poll(async () => {
      const response = await api.get(`/api/v1/toadie-connections/${connectionId}`);
      const state = await response.json() as { lastSuccessAt: number | null; lastErrorCode: string | null };
      return state.lastErrorCode ?? (state.lastSuccessAt == null ? "pending" : "ready");
    }, { timeout: 15_000 }).toBe("ready");

    await page.goto(`/contracts/${seeded.contractId}`);
    const usage = page.getByRole("region", { name: "Usage from Toadie", exact: true });
    await usage.getByRole("button", { name: "Edit Toadie links", exact: true }).click();
    const links = page.getByRole("dialog", { name: "Edit Toadie links", exact: true });
    await links.getByRole("combobox", { name: "Connection", exact: true }).click();
    await page.getByRole("option", { name: connectionName, exact: true }).click();
    await links.getByRole("checkbox", { name: "Orders API", exact: true }).check();
    await links.getByRole("checkbox", { name: "Order events", exact: true }).check();
    await links.getByRole("button", { name: "Save links", exact: true }).click();
    await expect(links).toHaveCount(0);
    const table = usage.getByRole("table", { name: "Usage from Toadie", exact: true });
    await expect(table.getByRole("row")).toHaveCount(3);
    await expect(table.getByRole("row").filter({ hasText: "Checkout service" })).toContainText("Provider");
    await expect(table.getByRole("row").filter({ hasText: "Storefront website" })).toContainText("Consumer");
    await expect(table.getByText("Unknown", { exact: true })).toHaveCount(4);
    await expect(table.getByRole("link", { name: "Open Commerce system in Toadie", exact: true })).toHaveCount(2);
    await expect(table.getByRole("link", { name: "Open Retail team in Toadie", exact: true })).toHaveCount(2);
    await expect(table.getByRole("link", { name: "Open Checkout service in Toadie", exact: true })).toHaveAttribute("href", `${upstream.browserUrl}/entities/3/edit`);
    const scan = await new AxeBuilder({ page }).include('[aria-label="Usage from Toadie"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await page.evaluate(() => window.scrollTo(0, 0));
    await testInfo.attach("Declared contract usage from Toadie", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  });

  test("a changed upstream revision restarts the scan and publishes one coherent observation", async ({ page }) => {
    await waitForRefreshCooldown(api, connectionId);
    upstream.clearRequests();
    upstream.renameServiceMidScan("Storefront application");
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    const usage = page.getByRole("region", { name: "Usage from Toadie", exact: true });
    await usage.getByRole("button", { name: "Refresh usage", exact: true }).click();
    const table = usage.getByRole("table", { name: "Usage from Toadie", exact: true });
    await expect(table.getByRole("link", { name: "Open Storefront application in Toadie", exact: true })).toHaveAttribute("href", `${upstream.browserUrl}/entities/4/edit`);
    await expect(table.getByRole("row")).toHaveCount(3);
    await expect(table).not.toContainText("Storefront website");
    await expect(usage.getByText("Current", { exact: true })).toBeVisible();

    const requests = upstream.requests();
    expect(new Set(requests.map(({ revision }) => revision)).size).toBeGreaterThanOrEqual(2);
    expect(requests.filter(({ blueprint, page: requestPage }) => blueprint == null && requestPage === 1).length).toBeGreaterThanOrEqual(2);
    const finalRevision = requests.at(-1)?.revision;
    expect(new Set(requests.filter(({ revision }) => revision === finalRevision).map(({ blueprint }) => blueprint))).toEqual(
      new Set([null, "api", "service", "system", "_team"]),
    );
  });

  test("continuously changing upstream revisions fail refresh and retain the previous observation", async ({ page }) => {
    await waitForRefreshCooldown(api, connectionId);
    upstream.clearRequests();
    upstream.setContinuousRevisionChanges();
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    const usage = page.getByRole("region", { name: "Usage from Toadie", exact: true });
    await usage.getByRole("button", { name: "Refresh usage", exact: true }).click();
    await expect(usage.getByText("Stale", { exact: true })).toBeVisible();
    await expect(usage).toContainText("The last refresh failed");
    await expect(usage.getByRole("link", { name: "Open Storefront application in Toadie", exact: true })).toBeVisible();
    await expect(usage.getByText("No declared usage observed", { exact: true })).toHaveCount(0);
    const requests = upstream.requests();
    expect(new Set(requests.map(({ revision }) => revision)).size).toBeGreaterThan(2);
    expect(requests.filter(({ blueprint }) => blueprint == null).length).toBeGreaterThanOrEqual(2);
  });

  test("a failed Toadie refresh keeps the last observed consumers visible and marks usage stale", async ({ page }) => {
    await waitForRefreshCooldown(api, connectionId);
    upstream.setFailure();
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    const usage = page.getByRole("region", { name: "Usage from Toadie", exact: true });
    await usage.getByRole("button", { name: "Refresh usage", exact: true }).click();
    await expect.poll(async () => {
      const response = await api.get(`/api/v1/toadie-connections/${connectionId}`);
      const state = await response.json() as { refreshing: boolean; lastErrorCode: string | null };
      return `${state.refreshing}:${state.lastErrorCode}`;
    }, { timeout: 15_000 }).toBe("false:GRAPHQL_ERROR");
    await expect(usage.getByText("Stale", { exact: true })).toBeVisible();
    await expect(usage).toContainText("The last refresh failed");
    await expect(usage.getByRole("link", { name: "Open Storefront application in Toadie", exact: true })).toBeVisible();
    await expect(usage.getByText("No declared usage observed", { exact: true })).toHaveCount(0);
  });
});
