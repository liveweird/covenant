import type { APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

/**
 * The way IN from Toadie: `/toadie/{blueprint}/{identifier}` resolves a Toadie entity to the
 * Covenant contract(s) that cover it. This suite adds its OWN entity to the fixture, carrying one
 * unique identifier under BOTH the `api` and `dataset` blueprints, so the blueprint filter is
 * proven end to end rather than by construction — and so a neighbouring spec linking the stock
 * `orders` cannot turn the single-match redirect into a chooser. Setup runs through the API (the
 * link editor itself is covered by `toadie-datasets.spec.ts`); the browser only drives the journey.
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
  // This suite's OWN entities, added to the fixture rather than reusing its stock `orders`. The
  // reverse read is keyed on (blueprint, identifier) alone — nothing scopes it to a connection or a
  // contract — so the stock identifier would also match whatever `toadie-usage`/`lifecycle-*` linked
  // on another worker, turning the single-match redirect this suite exists to prove into a chooser.
  // One identifier under BOTH blueprints, so the blueprint filter is still proven, not assumed.
  const ENTITY = uniqueText("orders").toLowerCase();
  // Numeric and past the fixture's stock 1..21: `ToadieGraphqlClient.id()` rejects any entity id
  // that is not a canonical positive integer, because that is what Port emits.
  const ENTITY_IDS = { api: "101", dataset: "102" } as const;
  const EXTRA_ENTITIES = [
    { id: ENTITY_IDS.api, blueprint: "api", identifier: ENTITY, title: "Orders API", relations: {} },
    { id: ENTITY_IDS.dataset, blueprint: "dataset", identifier: ENTITY, title: "Orders dataset", relations: {} },
  ];
  // Never linked by anyone, so the empty state is the only possible answer.
  const UNLINKED = uniqueText("unlinked").toLowerCase();

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
    upstream = await startToadieFixture({ datasets: true, extraEntities: EXTRA_ENTITIES });
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
    await page.goto(`/toadie/api/${ENTITY}`);
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded[0].contractId}$`));
    await expect(page.getByRole("heading", { level: 2, name: seeded[0].contractName, exact: true })).toBeVisible();
    await page.goBack();
    await expect(page).toHaveURL(/\/contracts$/);

    // The SAME identifier under `dataset` must reach the ODCS contract instead — proof that the
    // filter is the connection's target blueprint and not the identifier alone.
    await page.goto(`/toadie/dataset/${ENTITY}`);
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded[1].contractId}$`));
    await expect(page.getByRole("heading", { level: 2, name: seeded[1].contractName, exact: true })).toBeVisible();

    // An entity nothing covers is a plain empty state, never an error and never a 404 page.
    await page.goto(`/toadie/api/${UNLINKED}`);
    await expect(page.getByRole("heading", { level: 2, name: "Covenant contract", exact: true })).toBeVisible();
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
    await testInfo.attach("Unlinked Toadie entity", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });

    // An identifier that exists under no blueprint behaves the same way.
    await page.goto(`/toadie/api/${UNLINKED}-missing`);
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
  });

  test("an unauthenticated entity URL survives the sign-in round trip, percent-encoding included", async ({ page }) => {
    // Arriving from Toadie without a session must return to the SAME entity after login —
    // including an identifier whose encoded `/` has to stay part of the identifier.
    await page.goto("/login");
    await page.evaluate(() => localStorage.clear());
    await page.goto(`/toadie/api/team%2F${UNLINKED}`);
    await expect(page).toHaveURL(/\/login$/);
    await login(page);
    await expect(page).toHaveURL(new RegExp(`/toadie/api/team%2F${UNLINKED}$`));
    await expect(page.getByText(/No Covenant contract is linked to Toadie api/)).toBeVisible();
    // The identifier reached the server whole: a split on `/` would have asked for `team`.
    await expect(page.getByText(`team/${UNLINKED}`, { exact: true })).toBeVisible();
  });
});
