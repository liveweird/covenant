import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, APIResponse, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, openFilters, PETSTORE, seedContractViaApi, teardownSeededContract, test, type SeededContract, uniqueText } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

// Owns only the e2e-overview fixture. Its three versions remain DRAFT, so normal API cleanup
// can remove the contract and its registries without lifecycle transitions.
test.describe.serial("catalog lifecycle overview", () => {
  let api: APIRequestContext;
  let seeded: SeededContract;
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let connectionId = 0;

  const isoDate = (offsetDays: number) => {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() + offsetDays);
    return date.toISOString().slice(0, 10);
  };

  async function ok(response: APIResponse, status: number) {
    expect(response.status(), await response.text()).toBe(status);
  }

  async function addDraft(version: string) {
    await ok(await api.post(`/api/v1/contracts/${seeded.contractId}/versions`, {
      data: { version, content: PETSTORE(seeded.contractName, version) },
    }), 201);
  }

  async function setPolicy(major: number, body: Record<string, unknown>) {
    await ok(await api.put(`/api/v1/contracts/${seeded.contractId}/release-lines/${major}`, {
      data: { supportStatus: "SUPPORTED", ...body },
    }), 204);
  }

  const lineRow = (page: Page, major: number) => page.getByRole("table", { name: "Lifecycle overview" })
    .getByRole("row").filter({ has: page.getByRole("link", { name: seeded.contractName, exact: true }) })
    .filter({ has: page.getByText(`${major}.x`, { exact: true }) });

  test.beforeAll(async () => {
    upstream = await startToadieFixture();
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-overview", PETSTORE("Lifecycle overview", "1.0.0"));
    await addDraft("2.0.0");
    await addDraft("3.0.0");
    await setPolicy(1, { supportEndsOn: isoDate(-1), replacementContractId: seeded.contractId, replacementMajor: 3 });
    await setPolicy(2, {
      deprecatesOn: isoDate(7), supportEndsOn: isoDate(30), replacementContractId: seeded.contractId,
      replacementMajor: 3, migrationGuide: "Move consumers to 3.x before support ends.",
    });
    const connection = await api.post("/api/v1/toadie-connections", { data: {
      name: uniqueText("e2e-overview-toadie"), baseUrl: upstream.baseUrl, browserUrl: upstream.browserUrl,
      apiKey: upstream.key, enabled: true, refreshIntervalMinutes: 60,
      mapping: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
    } });
    await ok(connection, 201);
    connectionId = ((await connection.json()) as { id: number }).id;
    await expect.poll(async () => {
      const response = await api.get(`/api/v1/toadie-connections/${connectionId}`);
      const state = await response.json() as { lastSuccessAt: number | null; lastErrorCode: string | null };
      return state.lastErrorCode ?? (state.lastSuccessAt == null ? "pending" : "ready");
    }, { timeout: 15_000 }).toBe("ready");
  });

  test.afterAll(async () => {
    try {
      if (api && seeded) await teardownSeededContract(api, seeded);
      if (api && connectionId) await ok(await api.delete(`/api/v1/toadie-connections/${connectionId}`), 204);
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("an owner triages release lines and completes a migration plan from the overview", async ({ page }, testInfo) => {
    await login(page);
    await page.goto("/lifecycle");
    await expect(page.getByRole("heading", { name: "Lifecycle", exact: true })).toBeVisible();
    await openFilters(page);
    await page.getByLabel("Search", { exact: true }).fill(seeded.contractName);
    await expect(page.getByRole("link", { name: seeded.contractName, exact: true })).toHaveCount(3);
    await expect(page.getByRole("table", { name: "Lifecycle overview" }).getByRole("row")).toHaveCount(4);

    await expect(page.getByRole("button", { name: /1 Deadlines in next 30 days/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /1 Support-end date reached/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /1 Incomplete migration plan/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /3 Usage unavailable or stale/ })).toBeVisible();
    await page.getByRole("button", { name: /1 Support-end date reached/ }).click();
    await expect(lineRow(page, 1)).toBeVisible();
    await expect(lineRow(page, 2)).toHaveCount(0);
    await page.getByRole("button", { name: /1 Support-end date reached/ }).click();

    await page.getByRole("button", { name: `Edit policy for ${seeded.contractName} 1.x`, exact: true }).click();
    const policy = page.getByRole("dialog", { name: "Edit policy for 1.x", exact: true });
    await policy.getByLabel("Migration instructions", { exact: true }).fill("Move consumers to 3.x before support ends.");
    await policy.getByRole("button", { name: "Save policy", exact: true }).click();
    await expect(policy).toHaveCount(0);
    await expect(lineRow(page, 1)).toContainText("Complete");
    await expect(page.getByRole("button", { name: /0 Incomplete migration plan/ })).toBeVisible();

    await page.getByRole("combobox", { name: "Deadline", exact: true }).click();
    await page.getByRole("option", { name: "No dates set", exact: true }).click();
    await expect(lineRow(page, 3)).toBeVisible();
    await expect(lineRow(page, 1)).toHaveCount(0);
    await ok(await api.put(`/api/v1/contracts/${seeded.contractId}/toadie-links`, { data: { connectionId, apiEntityIds: ["1", "2"] } }), 204);
    await expect.poll(async () => {
      const response = await api.get(`/api/v1/contracts/${seeded.contractId}/toadie-links`);
      return ((await response.json()) as { cache: { state: string; refreshing: boolean } }).cache;
    }, { timeout: 15_000 }).toMatchObject({ state: "CURRENT", refreshing: false });
    await page.getByRole("button", { name: `Review impact for ${seeded.contractName} 3.x`, exact: true }).click();
    const impact = page.getByRole("dialog", { name: "Retirement impact for 3.x", exact: true });
    await expect(impact).toContainText(/whole contract.*version and release line are unknown/i);
    await expect(impact.getByText("Unknown", { exact: true })).toHaveCount(2);
    await impact.getByRole("button", { name: "Close", exact: true }).last().click();

    await page.getByLabel("Clear deadline filter", { exact: true }).click();
    await expect(lineRow(page, 1)).toBeVisible();
    await expect(lineRow(page, 1)).toContainText("1 consumer service");
    const scan = await new AxeBuilder({ page }).include('[aria-label="Lifecycle overview"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Lifecycle overview attention and migration plan", {
      body: await page.screenshot({ fullPage: true }),
      contentType: "image/png",
    });
  });
});
