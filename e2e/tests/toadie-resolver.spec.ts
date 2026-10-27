import type { APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

/**
 * The way IN from Toadie: `/toadie/{blueprint}/{identifier}` resolves a Toadie entity to the
 * Covenant contract(s) that cover it. The fixture deliberately carries the SAME identifier
 * (`orders`) under BOTH the `api` and `dataset` blueprints, so the blueprint filter is proven
 * end to end rather than by construction. Setup runs through the API (the link editor itself is
 * covered by `toadie-datasets.spec.ts`); the browser only drives the journey under test.
 */
test.describe.serial("Toadie contract resolver", () => {
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let api: APIRequestContext;
  const seeded: SeededContract[] = [];
  const connectionIds: number[] = [];

  const PRESETS = {
    api: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
    dataset: { serviceBlueprint: "service", apiBlueprint: "dataset", providesRelation: "produces_datasets", consumesRelation: "consumes_datasets", systemRelation: "system" },
  } as const;
  // Entity ids in the fixture: 1 is api/orders, 10 is dataset/orders — one identifier, two blueprints.
  const ENTITY_IDS = { api: "1", dataset: "10" } as const;

  async function connect(kind: "api" | "dataset"): Promise<number> {
    const created = await api.post("/api/v1/toadie-connections", {
      data: {
        name: uniqueText(`e2e-resolve-${kind}`),
        baseUrl: upstream.baseUrl,
        browserUrl: upstream.browserUrl,
        apiKey: upstream.key,
        enabled: true,
        refreshIntervalMinutes: 60,
        mapping: PRESETS[kind],
      },
    });
    expect(created.status(), await created.text()).toBe(201);
    const { id } = await created.json() as { id: number };
    connectionIds.push(id);
    await expect.poll(async () => {
      const state = await (await api.get(`/api/v1/toadie-connections/${id}`)).json() as { lastSuccessAt: number | null; lastErrorCode: string | null };
      return state.lastErrorCode ?? (state.lastSuccessAt == null ? "pending" : "ready");
    }, { timeout: 15_000 }).toBe("ready");
    return id;
  }

  test.beforeAll(async () => {
    upstream = await startToadieFixture({ datasets: true });
    ({ api } = await apiAsAdmin());
    seeded.push(await seedContractViaApi(api, "e2e-resolve-api", PETSTORE("Resolver API", "1.0.0")));
    const odcs = readFileSync(new URL("../../samples/contracts/specs/odcs-order-analytics-clean.yaml", import.meta.url), "utf8");
    seeded.push(await seedContractViaApi(api, "e2e-resolve-odcs", odcs, "ODCS"));

    for (const [index, kind] of (["api", "dataset"] as const).entries()) {
      const connectionId = await connect(kind);
      const linked = await api.put(`/api/v1/contracts/${seeded[index].contractId}/toadie-links`, {
        data: { connectionId, apiEntityIds: [ENTITY_IDS[kind]] },
      });
      expect(linked.status(), await linked.text()).toBe(204);
    }
  });

  test.afterAll(async () => {
    try {
      if (api) {
        for (const contract of seeded) await teardownSeededContract(api, contract);
        for (const id of connectionIds) {
          const response = await api.delete(`/api/v1/toadie-connections/${id}`);
          expect([204, 404]).toContain(response.status());
        }
      }
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
  });

  test("a Toadie entity URL lands on the contract that covers it, per blueprint", async ({ page }, testInfo) => {
    await login(page);

    // The `api` blueprint resolves to the OpenAPI contract and replaces the resolver in the history,
    // so Back returns to wherever the user came from rather than bouncing through this page.
    await page.goto("/contracts");
    await page.goto("/toadie/api/orders");
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded[0].contractId}$`));
    await expect(page.getByRole("heading", { level: 2, name: seeded[0].contractName, exact: true })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/contracts$/);

    // The SAME identifier under `dataset` must reach the ODCS contract instead — proof that the
    // filter is the connection's target blueprint and not the identifier alone.
    await page.goto("/toadie/dataset/orders");
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded[1].contractId}$`));
    await expect(page.getByRole("heading", { level: 2, name: seeded[1].contractName, exact: true })).toBeVisible();

    // An entity nothing covers is a plain empty state, never an error and never a 404 page.
    await page.goto("/toadie/api/events");
    await expect(page.getByRole("heading", { level: 2, name: "Covenant contract", exact: true })).toBeVisible();
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
    await testInfo.attach("Unlinked Toadie entity", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });

    // An identifier that exists under no blueprint behaves the same way.
    await page.goto("/toadie/api/does-not-exist");
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
  });

  test("an unauthenticated entity URL survives the sign-in round trip, percent-encoding included", async ({ page }) => {
    // Arriving from Toadie without a session must return to the SAME entity after login —
    // including an identifier whose encoded `/` has to stay part of the identifier.
    await page.goto("/login");
    await page.evaluate(() => localStorage.clear());
    await page.goto("/toadie/api/team%2Forders");
    await expect(page).toHaveURL(/\/login$/);
    await login(page);
    await expect(page).toHaveURL(/\/toadie\/api\/team%2Forders$/);
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
    // The identifier reached the server whole: a split on `/` would have asked for `team`.
    await expect(page.getByText("team/orders", { exact: true })).toBeVisible();
  });
});
