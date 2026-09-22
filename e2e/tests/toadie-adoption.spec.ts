import AxeBuilder from "@axe-core/playwright";
import { readFile } from "node:fs/promises";
import type { APIRequestContext, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, rowOperation, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

const adoptionMapping = (dataset: boolean) => ({
  blueprint: dataset ? "dataset_adoption" : "api_adoption",
  kind: dataset ? "DATASET_CONTRACT_VERSION" : "API_MAJOR_LINE",
  consumerRelation: "consumer", targetRelation: dataset ? "dataset" : "api", environmentRelation: "environment",
  valueProperty: dataset ? "contract_version" : "major_line", statusProperty: "status",
  declaredByProperty: "declared_by", verifiedAtProperty: "verified_at", notesProperty: "notes",
});

async function ready(api: APIRequestContext, id: number) {
  await expect.poll(async () => {
    const response = await api.get(`/api/v1/toadie-connections/${id}`);
    expect(response.status()).toBe(200);
    const state = await response.json() as { lastSuccessAt: number | null; lastErrorCode: string | null; refreshing: boolean };
    return state.lastErrorCode ?? (state.lastSuccessAt == null || state.refreshing ? "pending" : "ready");
  }, { timeout: 15_000 }).toBe("ready");
}

const declarations = (page: Page) => page.getByRole("region", { name: "Declared adoption from Toadie", exact: true });

test.describe.serial("declared Toadie adoption", () => {
  let api: APIRequestContext;
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  const seeded: SeededContract[] = [];
  const connections: { id: number; name: string; body: object }[] = [];
  let readerId: number | undefined;
  const readerEmail = `${uniqueText("e2e-adoption-reader")}@covenant.local`;
  const readerPassword = "e2e-only-password";

  test.beforeAll(async () => {
    upstream = await startToadieFixture({ adoptions: true });
    ({ api } = await apiAsAdmin());
    const odcs = await readFile(new URL("../../samples/contracts/specs/odcs-order-analytics-clean.yaml", import.meta.url), "utf8");
    seeded.push(await seedContractViaApi(api, "e2e-adoption-api", PETSTORE("Adoption API", "1.0.0")));
    seeded.push(await seedContractViaApi(api, "e2e-adoption-data", odcs, "ODCS"));
    for (const [index, contract] of seeded.entries()) {
      const name = uniqueText(`e2e-adoption-${index}`);
      const body = {
        name, baseUrl: upstream.baseUrl, browserUrl: upstream.browserUrl, apiKey: upstream.key,
        enabled: true, refreshIntervalMinutes: 60,
        mapping: { serviceBlueprint: "service", apiBlueprint: index ? "dataset" : "api", providesRelation: index ? "produces_datasets" : "provides_apis", consumesRelation: index ? "consumes_datasets" : "consumes_apis", systemRelation: "system" },
        adoptionMapping: index ? adoptionMapping(true) : null,
      };
      const response = await api.post("/api/v1/toadie-connections", { data: body });
      expect(response.status(), await response.text()).toBe(201);
      const { id } = await response.json() as { id: number };
      connections.push({ id, name, body });
      await ready(api, id);
      expect((await api.put(`/api/v1/contracts/${contract.contractId}/toadie-links`, { data: { connectionId: id, apiEntityIds: index ? ["10", "11"] : ["1", "2"] } })).status()).toBe(204);
    }
    const created = await api.post("/api/v1/users", { data: { name: uniqueText("e2e-adoption-reader"), email: readerEmail, password: readerPassword, roles: [] } });
    expect(created.status()).toBe(201);
    readerId = (await created.json() as { id: number }).id;
  });

  test.afterAll(async () => {
    try {
      if (api) {
        for (const contract of [...seeded].reverse()) await teardownSeededContract(api, contract);
        for (const connection of connections) expect([204, 404]).toContain((await api.delete(`/api/v1/toadie-connections/${connection.id}`)).status());
        if (readerId !== undefined) expect([204, 404]).toContain((await api.delete(`/api/v1/users/${readerId}`)).status());
      }
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("an administrator opts into parallel API declarations without changing architecture usage", async ({ page }, testInfo) => {
    const path = `/api/v1/contracts/${seeded[0].contractId}/toadie-adoptions`;
    expect((await (await api.get(path)).json() as { availability: string }).availability).toBe("NOT_CONFIGURED");
    await login(page);
    await page.goto("/toadie-connections");
    await rowOperation(page, connections[0].name, "Edit");
    const editor = page.getByRole("dialog", { name: "Edit Toadie connection", exact: true });
    await editor.getByRole("button", { name: "Declared adoption mapping", exact: true }).click();
    await editor.getByRole("switch", { name: /^Read declared adoption/ }).check();
    await editor.getByRole("button", { name: "Use API adoption mapping", exact: true }).click();
    const [saved] = await Promise.all([
      page.waitForResponse((response) => response.request().method() === "PUT" && new URL(response.url()).pathname === `/api/v1/toadie-connections/${connections[0].id}`),
      editor.getByRole("button", { name: "Save", exact: true }).click(),
    ]);
    expect(saved.status()).toBe(204);
    await expect(editor).toHaveCount(0);
    await ready(api, connections[0].id);
    await page.goto(`/contracts/${seeded[0].contractId}`);
    const section = declarations(page);
    const table = section.getByRole("table", { name: "Declared adoption from Toadie", exact: true });
    await expect(table.getByRole("row")).toHaveCount(5);
    for (const value of ["v1", "v2", "v3", "Production", "All environments", "architecture.team", "Unknown"]) await expect(table).toContainText(value);
    await expect(section).toContainText(/consumption relationship/i);
    const architecture = page.getByRole("table", { name: "Usage from Toadie", exact: true });
    await expect(architecture.getByRole("row")).toHaveCount(3);
    await expect(architecture).not.toContainText("Database-only service");
    const scan = await new AxeBuilder({ page }).include('[aria-label="Declared adoption from Toadie"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Parallel declared API adoption", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  });

  test("a reader sees dataset declarations and downloads all of them from lifecycle impact", async ({ page }, testInfo) => {
    await login(page, readerEmail, readerPassword);
    await page.goto(`/contracts/${seeded[1].contractId}`);
    const section = declarations(page);
    const table = section.getByRole("table", { name: "Declared adoption from Toadie", exact: true });
    await expect(table.getByRole("row")).toHaveCount(3);
    for (const value of ["1.0.0", "Unknown", "Production", "All environments", "data.team"]) await expect(table).toContainText(value);
    await expect(table.locator("script")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Edit Toadie links", exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Review retirement impact for 1.x", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Retirement impact for 1.x", exact: true });
    await expect(dialog.getByRole("table", { name: "Declared adoption from Toadie", exact: true })).toContainText("1.0.0");
    await dialog.getByRole("table", { name: "Declared adoption from Toadie", exact: true }).scrollIntoViewIfNeeded();
    await testInfo.attach("Dataset adoption in lifecycle impact", { body: await dialog.screenshot(), contentType: "image/png" });
    const downloaded = page.waitForEvent("download");
    await dialog.getByRole("button", { name: "Download report", exact: true }).click();
    const download = await downloaded;
    const path = await download.path();
    expect(path).not.toBeNull();
    const markdown = await readFile(path!, "utf8");
    const plain = markdown.replace(/\\([\\`*_{}[\]()<>#+\-.!|&~])/g, "$1");
    for (const value of ["Declared dataset adoption", "Undeclared dataset version", "1.0.0", "Production", "data.team", "2026-08-02T09:00:00.000Z"]) expect(plain).toContain(value);
    expect(markdown).not.toContain(upstream.key);
    expect(markdown.includes("\\<script\\>") || markdown.includes("```text\nKeep `orders` compatible <script>not executable</script>\n```")).toBe(true);
    expect((await (await api.get(`/api/v1/contracts/${seeded[1].contractId}/versions/${seeded[1].versionId}`)).json() as { lifecycle: string }).lifecycle).toBe("DRAFT");
    await testInfo.attach("Declared dataset adoption report", { body: markdown, contentType: "text/markdown" });
  });

  test("a failed Toadie refresh retains declared adoption with a stale warning", async ({ page }) => {
    upstream.setFailure();
    const connection = connections[1];
    expect((await api.put(`/api/v1/toadie-connections/${connection.id}`, { data: { ...connection.body, name: `${connection.name}-failed` } })).status()).toBe(204);
    await expect.poll(async () => (await (await api.get(`/api/v1/toadie-connections/${connection.id}`)).json() as { lastErrorCode: string | null }).lastErrorCode).not.toBeNull();
    await login(page, readerEmail, readerPassword);
    await page.goto(`/contracts/${seeded[1].contractId}`);
    const section = declarations(page);
    await expect(section).toContainText(/stale/i);
    await expect(section.getByRole("table", { name: "Declared adoption from Toadie", exact: true })).toContainText("1.0.0");
  });
});

test("an absent optional adoption blueprint keeps architecture usage available", async ({ page }) => {
  const upstream = await startToadieFixture();
  const { api } = await apiAsAdmin();
  let seeded: SeededContract | undefined;
  let connectionId: number | undefined;
  try {
    seeded = await seedContractViaApi(api, "e2e-adoption-absent", PETSTORE("Optional adoption", "1.0.0"));
    const created = await api.post("/api/v1/toadie-connections", { data: {
      name: uniqueText("e2e-adoption-absent"), baseUrl: upstream.baseUrl, browserUrl: upstream.browserUrl,
      apiKey: upstream.key, enabled: true, refreshIntervalMinutes: 60, adoptionMapping: adoptionMapping(false),
    } });
    expect(created.status(), await created.text()).toBe(201);
    connectionId = (await created.json() as { id: number }).id;
    await ready(api, connectionId);
    expect((await api.put(`/api/v1/contracts/${seeded.contractId}/toadie-links`, { data: { connectionId, apiEntityIds: ["1"] } })).status()).toBe(204);
    const response = await api.get(`/api/v1/contracts/${seeded.contractId}/toadie-adoptions`);
    expect(response.status()).toBe(200);
    expect(await response.json()).toMatchObject({ availability: "BLUEPRINT_MISSING", items: [], cache: { state: "CURRENT" } });
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}`);
    await expect(declarations(page)).toContainText(/blueprint/i);
    await expect(page.getByRole("table", { name: "Usage from Toadie", exact: true })).toContainText("Storefront website");
  } finally {
    try {
      if (seeded) await teardownSeededContract(api, seeded);
      if (connectionId !== undefined) expect([204, 404]).toContain((await api.delete(`/api/v1/toadie-connections/${connectionId}`)).status());
    } finally {
      await api.dispose();
      await upstream.close();
    }
  }
});
