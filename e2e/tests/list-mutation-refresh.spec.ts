import type { Page, Route } from "@playwright/test";
import { apiAsAdmin, expect, login, openFilters, rowOperation, test, uniqueText } from "./helpers";

type Registry = "users" | "teams" | "domains" | "systems" | "contracts" | "toadie-connections";

async function verifyPendingListDelete(page: Page, registry: Registry) {
  const { api, userId } = await apiAsAdmin();
  const ownedPaths: string[] = [];
  const name = uniqueText(`e2e-race-${registry}`);
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  let markHeld!: () => void;
  const held = new Promise<void>((resolve) => { markHeld = resolve; });
  let markSettled!: () => void;
  const settled = new Promise<void>((resolve) => { markSettled = resolve; });
  let intercepted = false;
  const field = registry === "contracts" ? "q" : "name";
  const isTarget = (url: string) => {
    const parsed = new URL(url);
    return parsed.pathname === `/api/v1/${registry}` && (registry === "toadie-connections"
      ? parsed.searchParams.get("sort") === "-name"
      : parsed.searchParams.get(field) === name);
  };
  const handler = async (route: Route) => {
    if (route.request().method() !== "GET" || !isTarget(route.request().url()) || intercepted) {
      await route.continue();
      return;
    }
    intercepted = true;
    try {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      expect((await response.json() as { items: { name: string }[] }).items.some((item) => item.name === name)).toBe(true);
      markHeld();
      await gate;
      await route.fulfill({ response });
    } finally {
      markSettled();
    }
  };
  async function create(resource: Registry, data: object) {
    const response = await api.post(`/api/v1/${resource}`, { data });
    expect(response.status(), await response.text()).toBe(201);
    const { id } = await response.json() as { id: number };
    ownedPaths.unshift(`/api/v1/${resource}/${id}`);
    return id;
  }
  async function seed() {
    if (registry === "users") return create(registry, { name, email: `${name}@covenant.local`, password: "e2e-only-password", roles: [] });
    if (registry === "teams" || registry === "domains") return create(registry, { name });
    if (registry === "toadie-connections") return create(registry, {
      name, baseUrl: "http://host.docker.internal:1", browserUrl: "http://localhost:1",
      apiKey: "e2e-only-key", enabled: false, refreshIntervalMinutes: 60,
      mapping: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
    });
    const domainId = await create("domains", { name: uniqueText("e2e-race-parent-domain") });
    if (registry === "systems") return create(registry, { name, domainId });
    const systemId = await create("systems", { name: uniqueText("e2e-race-parent-system"), domainId });
    return create("contracts", { name, systemId, type: "OPENAPI", ownerUserId: userId });
  }
  try {
    const id = await seed();
    await login(page);
    await page.goto(`/${registry}`);
    const row = page.getByRole("row", { name: new RegExp(name) });
    if (registry !== "toadie-connections") {
      await openFilters(page);
      const prefix = name.slice(0, -1);
      const initial = page.waitForResponse((response) => {
        const url = new URL(response.url());
        return response.request().method() === "GET" && url.pathname === `/api/v1/${registry}` && url.searchParams.get(field) === prefix && response.ok();
      });
      await page.getByLabel(registry === "contracts" ? "Search" : "Name", { exact: true }).fill(prefix);
      await (await initial).finished();
    }
    await expect(row).toBeVisible();
    await page.route(`**/api/v1/${registry}?*`, handler);
    if (registry === "toadie-connections") await page.getByRole("button", { name: "Name", exact: true }).click();
    else await page.getByLabel(registry === "contracts" ? "Search" : "Name", { exact: true }).fill(name);
    await held;
    await rowOperation(page, name, registry === "toadie-connections" ? "Delete" : `Delete ${name}`);
    const [deleted] = await Promise.all([
      page.waitForResponse((response) => response.request().method() === "DELETE" && new URL(response.url()).pathname === `/api/v1/${registry}/${id}`),
      page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
    ]);
    expect(deleted.status()).toBe(204);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect((await api.get(`/api/v1/${registry}/${id}`)).status()).toBe(404);
    release();
    await settled;
    await expect(row).toHaveCount(0);
    if (registry === "toadie-connections") await expect(page.getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "descending");
    else await expect(page.getByLabel(registry === "contracts" ? "Search" : "Name", { exact: true })).toHaveValue(name);
  } finally {
    try {
      release();
      if (intercepted) await settled;
      if (!page.isClosed()) await page.unroute(`**/api/v1/${registry}?*`, handler);
    } finally {
      try {
        for (const path of ownedPaths) {
          const response = await api.delete(path);
          expect([204, 404], `${path}: ${response.status()}`).toContain(response.status());
        }
      } finally {
        await api.dispose();
      }
    }
  }
}

test("a pending Users filter cannot restore a deleted user", async ({ page }) => { await verifyPendingListDelete(page, "users"); });
test("a pending Teams filter cannot restore a deleted team", async ({ page }) => { await verifyPendingListDelete(page, "teams"); });
test("a pending Domains filter cannot restore a deleted domain", async ({ page }) => { await verifyPendingListDelete(page, "domains"); });
test("a pending Systems filter cannot restore a deleted system", async ({ page }) => { await verifyPendingListDelete(page, "systems"); });
test("a pending Contracts search cannot restore a deleted contract", async ({ page }) => { await verifyPendingListDelete(page, "contracts"); });
test("a pending Toadie connections sort cannot restore a deleted connection", async ({ page }) => { await verifyPendingListDelete(page, "toadie-connections"); });

test("a pending Feature Flags filter cannot undo a successful toggle", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const name = uniqueText("e2e-race-flags");
  let userId: number | undefined;
  let release!: () => void;
  const gate = new Promise<void>((resolve) => { release = resolve; });
  let markHeld!: () => void;
  const held = new Promise<void>((resolve) => { markHeld = resolve; });
  let markSettled!: () => void;
  const settled = new Promise<void>((resolve) => { markSettled = resolve; });
  let intercepted = false;
  const handler = async (route: Route) => {
    if (route.request().method() !== "GET" || new URL(route.request().url()).searchParams.get("name") !== name || intercepted) {
      await route.continue();
      return;
    }
    intercepted = true;
    try {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      expect((await response.json() as { items: { id: number }[] }).items.some((item) => item.id === userId)).toBe(true);
      markHeld();
      await gate;
      await route.fulfill({ response });
    } finally {
      markSettled();
    }
  };
  try {
    const created = await api.post("/api/v1/users", { data: { name, email: `${name}@covenant.local`, password: "e2e-only-password", roles: [] } });
    expect(created.status()).toBe(201);
    userId = (await created.json() as { id: number }).id;
    expect((await api.put(`/api/v1/users/${userId}/features`, { data: { disabledFeatures: ["MFA"] } })).status()).toBe(204);
    await login(page);
    await page.goto("/feature-flags");
    await openFilters(page);
    const prefix = name.slice(0, -1);
    const initial = page.waitForResponse((response) => response.request().method() === "GET" && new URL(response.url()).pathname === "/api/v1/users" && new URL(response.url()).searchParams.get("name") === prefix && response.ok());
    await page.getByLabel("Name", { exact: true }).fill(prefix);
    await (await initial).finished();
    const toggle = page.getByRole("switch", { name: `Toggle Email MFA for ${name}`, exact: true });
    await expect(toggle).not.toBeChecked();
    await expect(toggle).toBeVisible();
    await page.route("**/api/v1/users?*", handler);
    await page.getByLabel("Name", { exact: true }).fill(name);
    await held;
    const [updated] = await Promise.all([
      page.waitForResponse((response) => response.request().method() === "PUT" && new URL(response.url()).pathname === `/api/v1/users/${userId}/features`),
      toggle.click({ force: true }),
    ]);
    expect(updated.status()).toBe(204);
    expect((await (await api.get(`/api/v1/users/${userId}`)).json() as { disabledFeatures: string[] }).disabledFeatures).not.toContain("MFA");
    release();
    await settled;
    await expect(toggle).toBeChecked();
    await expect(toggle).toBeEnabled();
    await expect(page.getByLabel("Name", { exact: true })).toHaveValue(name);
  } finally {
    try {
      release();
      if (intercepted) await settled;
      if (!page.isClosed()) await page.unroute("**/api/v1/users?*", handler);
    } finally {
      try {
        if (userId !== undefined) expect([204, 404]).toContain((await api.delete(`/api/v1/users/${userId}`)).status());
      } finally {
        await api.dispose();
      }
    }
  }
});
