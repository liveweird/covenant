import AxeBuilder from "@axe-core/playwright";
import { readFileSync } from "node:fs";
import type { APIRequestContext } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

test.describe("Toadie dataset usage", () => {
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let api: APIRequestContext;
  const seeded: SeededContract[] = [];
  const connectionIds: number[] = [];

  test.beforeAll(async () => {
    upstream = await startToadieFixture({ datasets: true });
    ({ api } = await apiAsAdmin());
    seeded.push(await seedContractViaApi(api, "e2e-dataset-api", PETSTORE("Dataset companion API", "1.0.0")));
    const document = readFileSync(new URL("../../samples/contracts/specs/odcs-order-analytics-clean.yaml", import.meta.url), "utf8");
    seeded.push(await seedContractViaApi(api, "e2e-dataset-odcs", document, "ODCS"));
  });

  test.afterAll(async () => {
    try {
      if (api) {
        for (const contract of seeded) await teardownSeededContract(api, contract);
        for (const id of connectionIds) {
          const response = await api.delete(`/api/v1/toadie-connections/${id}`);
          expect(response.status(), await response.text()).toBe(204);
        }
      }
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("API and ODCS contracts use separate mappings to the same Toadie instance", async ({ page }, testInfo) => {
    await login(page);
    const names = [uniqueText("e2e-api-mapping"), uniqueText("e2e-dataset-mapping")];
    for (const [index, name] of names.entries()) {
      await page.goto("/toadie-connections");
      await page.getByRole("button", { name: "New connection", exact: true }).click();
      const dialog = page.getByRole("dialog", { name: "New Toadie connection", exact: true });
      await dialog.getByRole("textbox", { name: "Name", exact: true }).fill(name);
      await dialog.getByRole("textbox", { name: "Backend base URL", exact: true }).fill(upstream.baseUrl);
      await dialog.getByRole("textbox", { name: "Browser URL", exact: true }).fill(upstream.browserUrl);
      await dialog.getByRole("textbox", { name: "API key", exact: true }).fill(upstream.key);
      await dialog.getByRole("button", { name: "Advanced mapping", exact: true }).click();
      await dialog.getByRole("button", { name: index === 0 ? "Use API mapping" : "Use dataset mapping", exact: true }).click();
      const [created] = await Promise.all([
        page.waitForResponse((response) => response.url().endsWith("/api/v1/toadie-connections") && response.request().method() === "POST"),
        dialog.getByRole("button", { name: "Create", exact: true }).click(),
      ]);
      expect(created.status(), await created.text()).toBe(201);
      const connection = await created.json() as { id: number; mapping: { apiBlueprint: string }; apiKey?: string };
      connectionIds.push(connection.id);
      expect(connection.mapping.apiBlueprint).toBe(index === 0 ? "api" : "dataset");
      expect(connection.apiKey).toBeUndefined();
      await expect(dialog).toHaveCount(0);
      await expect.poll(async () => {
        const state = await (await api.get(`/api/v1/toadie-connections/${connection.id}`)).json() as { lastSuccessAt: number | null; lastErrorCode: string | null };
        return state.lastErrorCode ?? (state.lastSuccessAt == null ? "pending" : "ready");
      }, { timeout: 15_000 }).toBe("ready");

      await page.goto(`/contracts/${seeded[index].contractId}`);
      const usage = page.getByRole("region", { name: "Usage from Toadie", exact: true });
      await usage.getByRole("button", { name: "Edit Toadie links", exact: true }).click();
      const links = page.getByRole("dialog", { name: "Edit Toadie links", exact: true });
      await links.getByRole("combobox", { name: "Connection", exact: true }).click();
      await page.getByRole("option", { name, exact: true }).click();
      if (index === 0) {
        await links.getByRole("checkbox", { name: "Orders API", exact: true }).check();
        await expect(links.getByRole("checkbox", { name: "Orders dataset", exact: true })).toHaveCount(0);
      } else {
        await links.getByRole("checkbox", { name: "Orders dataset", exact: true }).check();
        await links.getByRole("checkbox", { name: "Daily settlements", exact: true }).check();
        await expect(links.getByRole("checkbox", { name: "Orders API", exact: true })).toHaveCount(0);
      }
      await links.getByRole("button", { name: "Save links", exact: true }).click();
      await expect(links).toHaveCount(0);
      const table = usage.getByRole("table", { name: "Usage from Toadie", exact: true });
      if (index === 0) {
        await expect(table.getByRole("row")).toHaveCount(3);
        await expect(table).toContainText("Checkout service");
      } else {
        await expect(table.getByRole("row")).toHaveCount(2);
        const pipeline = table.getByRole("row").filter({ hasText: "Settlement pipeline" });
        await expect(pipeline).toContainText("Provider");
        await expect(pipeline).toContainText("Consumer");
        await expect(table.getByText("Unknown", { exact: true })).toHaveCount(2);
        await expect(table).not.toContainText("Database-only service");
        await expect(table).not.toContainText("Checkout service");
        await expect(usage.getByText("Linked APIs or datasets:", { exact: true })).toBeVisible();
        await expect(usage.getByRole("link", { name: "Open Orders dataset in Toadie", exact: true })).toHaveAttribute("href", `${upstream.browserUrl}/entities/10/edit`);
        const scan = await new AxeBuilder({ page }).include('[aria-label="Usage from Toadie"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
        expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
        await expect(page.getByText("Toadie API or dataset links updated", { exact: true })).toBeVisible();
        await page.evaluate(() => window.scrollTo(0, 0));
        await testInfo.attach("ODCS datasets and declared usage", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
      }
    }
    // Revisit the API contract after linking datasets to verify that the second mapping did not replace it.
    await page.goto(`/contracts/${seeded[0].contractId}`);
    const table = page.getByRole("region", { name: "Usage from Toadie", exact: true }).getByRole("table");
    await expect(table.getByRole("row")).toHaveCount(3);
    await expect(table).toContainText("Storefront website");
    await expect(table).not.toContainText("Settlement pipeline");
    expect(new Set(upstream.requests().map(({ blueprint }) => blueprint))).toEqual(new Set([null, "api", "dataset", "service", "system", "_team"]));
  });
});
