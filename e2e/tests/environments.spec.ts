// The environments registry: an admin creates an environment for a throwaway system with an
// HTTP and a PostgreSQL target (the stack's own services), edits it leaving the password blank
// (the badge stays — the stored secret is kept), and deletes it; a regular user reads the list
// without controls. Owns: its throwaway domain/system/environment/user (unique `e2e-*` names).
import type { Page } from "@playwright/test";
import { createUserViaUi, deleteUserRow, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

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
