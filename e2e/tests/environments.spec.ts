// The environments registry: an admin creates an environment for a throwaway system with an
// HTTP and a PostgreSQL target (the stack's own services), edits it leaving the password blank
// (the badge stays — the stored secret is kept), and deletes it; a regular user reads the list
// without controls. Owns: its throwaway domain/system/environment/user (unique `e2e-*` names).
import type { Page, Route } from "@playwright/test";
import { apiAsAdmin, createUserViaUi, deleteUserRow, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

async function confirmDelete(page: Page, urlPattern: RegExp) {
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && urlPattern.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

async function createDomainAndSystem(page: Page, domainName: string, systemName: string) {
  await page.goto("/domains");
  await page.getByRole("button", { name: "New domain" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(domainName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/domains") && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await page.goto("/systems");
  await page.getByRole("button", { name: "New system" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("combobox", { name: "Domain" }).click();
  await dialog.getByRole("combobox", { name: "Domain" }).fill(domainName);
  await page.getByRole("option", { name: domainName }).click();
  await dialog.getByLabel("Name").fill(systemName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/systems") && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
}

async function deleteRegistryRow(page: Page, path: string, name: string, urlPattern: RegExp) {
  await page.goto(path);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await confirmDelete(page, urlPattern);
}

test("admin creates an environment with HTTP and PostgreSQL targets, edits it keeping the stored password, and deletes it", async ({ page }) => {
  await login(page);
  const domainName = uniqueText("e2e-dom-e");
  const systemName = uniqueText("e2e-sys-e");
  const envName = uniqueText("e2e-env");
  await createDomainAndSystem(page, domainName, systemName);

  await page.goto("/environments");
  await page.getByRole("button", { name: "New environment" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("combobox", { name: "System" }).click();
  await dialog.getByRole("combobox", { name: "System" }).fill(systemName);
  await page.getByRole("option", { name: `${domainName} / ${systemName}` }).click();
  await dialog.getByLabel("Name", { exact: true }).fill(envName);
  await dialog.getByLabel("Base URL").fill("http://checker:9090");
  await dialog.getByLabel("PostgreSQL target (ODCS)").check();
  await dialog.getByLabel("JDBC URL").fill("jdbc:postgresql://postgres:5432/covenant");
  await dialog.getByLabel("Username").fill("covenant");
  await dialog.getByRole("textbox", { name: "Password" }).fill("covenant");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/environments") && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(envName);
  const row = page.getByRole("row", { name: new RegExp(envName) });
  await expect(row).toContainText("HTTP");
  await expect(row).toContainText("PostgreSQL");

  // Edit: the password field starts blank and says so; saving keeps the stored secret.
  await rowOperation(page, envName, `Edit ${envName}`);
  const editor = page.getByRole("dialog");
  await expect(editor.getByText("Leave blank to keep the stored password")).toBeVisible();
  await editor.getByLabel("Description").fill("edited by e2e");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/environments\/\d+$/.test(r.url()) && r.ok()),
    editor.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByRole("row", { name: new RegExp(envName) })).toContainText("PostgreSQL");
  await expect(page.getByRole("row", { name: new RegExp(envName) })).toContainText("edited by e2e");

  await rowOperation(page, envName, `Delete ${envName}`);
  await confirmDelete(page, /\/api\/v1\/environments\/\d+$/);
  await expect(page.getByRole("row", { name: new RegExp(envName) })).toHaveCount(0);
  await deleteRegistryRow(page, "/systems", systemName, /\/api\/v1\/systems\/\d+$/);
  await deleteRegistryRow(page, "/domains", domainName, /\/api\/v1\/domains\/\d+$/);
});

test("a regular user reads the environments list without controls", async ({ page }) => {
  await login(page);
  const reader = await createUserViaUi(page, "E2E Env Reader");
  await signOut(page);
  await login(page, reader.email, reader.password);
  await page.goto("/environments");
  await expect(page.getByRole("heading", { name: "Environments" })).toBeVisible();
  await expect(page.getByRole("button", { name: "New environment" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: /^Operations for/ })).toHaveCount(0);
  await signOut(page);
  await login(page);
  await page.goto("/users");
  await deleteUserRow(page, reader.name);
});


test("deleting an environment while its first filtered response is pending cannot restore the deleted row", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const ownedPaths: string[] = [];
  const envName = uniqueText("e2e-env-race");
  let releaseResponse!: () => void;
  const responseGate = new Promise<void>((resolve) => { releaseResponse = resolve; });
  let markHeld!: () => void;
  const responseHeld = new Promise<void>((resolve) => { markHeld = resolve; });
  let markSettled!: () => void;
  const responseSettled = new Promise<void>((resolve) => { markSettled = resolve; });
  let intercepted = false;
  const handler = async (route: Route) => {
    const request = route.request();
    if (request.method() !== "GET" || new URL(request.url()).searchParams.get("name") !== envName || intercepted) {
      await route.continue();
      return;
    }
    intercepted = true;
    try {
      // Capture a real pre-delete result, then deliver it after the mutation succeeds.
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      expect((await response.json() as { items: { name: string }[] }).items.map((row) => row.name)).toEqual([envName]);
      markHeld();
      await responseGate;
      await route.fulfill({ response });
    } finally {
      markSettled();
    }
  };
  async function create(path: string, data: object) {
    const response = await api.post(`/api/v1/${path}`, { data });
    expect(response.status(), await response.text()).toBe(201);
    const { id } = await response.json() as { id: number };
    ownedPaths.unshift(`/api/v1/${path}/${id}`);
    return id;
  }
  try {
    const domainId = await create("domains", { name: uniqueText("e2e-env-race-dom") });
    const systemId = await create("systems", { domainId, name: uniqueText("e2e-env-race-sys") });
    const environmentId = await create("environments", { systemId, name: envName, httpBaseUrl: "http://checker:9090" });
    await login(page);
    await page.goto("/environments");
    const row = page.getByRole("row", { name: new RegExp(envName) });
    await expect(row).toBeVisible();
    await page.route("**/api/v1/environments?*", handler);
    await openFilters(page);
    await page.getByLabel("Name", { exact: true }).fill(envName);
    await responseHeld;
    // The previous page remains actionable while the new filtered query has no cached data.
    await rowOperation(page, envName, `Delete ${envName}`);
    await confirmDelete(page, new RegExp(`/api/v1/environments/${environmentId}$`));
    await expect(page.getByRole("dialog")).toHaveCount(0);
    expect((await api.get(`/api/v1/environments/${environmentId}`)).status()).toBe(404);
    releaseResponse();
    await responseSettled;
    await expect(row).toHaveCount(0);
    await expect(page.getByLabel("Name", { exact: true })).toHaveValue(envName);
  } finally {
    releaseResponse();
    if (intercepted) await responseSettled;
    await page.unroute("**/api/v1/environments?*", handler);
    try {
      for (const path of ownedPaths) {
        const response = await api.delete(path);
        expect([204, 404], `${path}: ${response.status()}`).toContain(response.status());
      }
    } finally {
      await api.dispose();
    }
  }
});
