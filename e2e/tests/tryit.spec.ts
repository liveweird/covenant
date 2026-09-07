// Try it: an ODCS contract declaring the stack's own `users` table is read through an environment
// pointing at the compose Postgres, and the sample is measured against the declared columns —
// a declared column the table lacks is a COLUMN_MISSING finding. Owns: its throwaway domain,
// system, team, environment and contract (unique `e2e-*` names), all deleted by the end.
import type { Page } from "@playwright/test";
import { createPostgresEnvironment, expect, login, openFilters, rowOperation, test, uniqueText } from "./helpers";

const USERS_CONTRACT = (name: string) => `apiVersion: v3.1.0
kind: DataContract
id: ${name}
version: 1.0.0
status: active
name: ${name}
description:
  purpose: The accounts of this very deployment.
schema:
  - name: users
    physicalType: table
    logicalType: object
    properties:
      - name: id
        logicalType: integer
        required: true
      - name: email
        logicalType: string
        required: true
      - name: name
        logicalType: string
      - name: bogus
        logicalType: string
`;

async function fillEditor(page: Page, text: string) {
  const editor = page.getByRole("textbox", { name: "Contract document" });
  await editor.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(text);
}

async function pickOption(page: Page, label: string, value: string) {
  const combo = page.getByRole("combobox", { name: label });
  await combo.click();
  await combo.fill(value);
  await page.getByRole("option", { name: value, exact: true }).click();
}

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

async function createTeam(page: Page, teamName: string) {
  await page.goto("/teams");
  await page.getByRole("button", { name: "New team" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(teamName);
  await page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click();
  await expect(page).toHaveURL(/\/teams\/\d+$/);
}

async function deleteRegistryRow(page: Page, path: string, name: string, urlPattern: RegExp) {
  await page.goto(path);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await confirmDelete(page, urlPattern);
}

test("admin reads the users table through an environment and the sample is measured against the ODCS contract", async ({ page }) => {
  await login(page);
  const domainName = uniqueText("e2e-dom-t");
  const systemName = uniqueText("e2e-sys-t");
  const teamName = uniqueText("e2e-team-t");
  const envName = uniqueText("e2e-env-t");
  const contractName = uniqueText("e2e-accounts");
  await createDomainAndSystem(page, domainName, systemName);
  await createTeam(page, teamName);
  await createPostgresEnvironment(page, domainName, systemName, envName);

  // The ODCS contract, imported: the document's name prefills the contract's.
  await page.goto("/contracts/import");
  await page.getByRole("combobox", { name: "Type" }).click();
  await page.getByRole("option", { name: "ODCS", exact: true }).click();
  await fillEditor(page, USERS_CONTRACT(contractName));
  await expect(page.getByLabel("Name", { exact: true })).toHaveValue(contractName);
  await pickOption(page, "System", systemName);
  await pickOption(page, "Owner", teamName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/import") && r.ok()),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  await page.getByRole("link", { name: "Open the version" }).click();
  await expect(page.getByRole("heading", { name: `${contractName} 1.0.0` })).toBeVisible();

  // Try it: the environment is preselected (the only one with a PostgreSQL target), the dataset too.
  await page.getByRole("button", { name: "Try it" }).click();
  const drawer = page.getByRole("dialog");
  await expect(drawer.getByRole("heading", { name: `Try ${contractName} 1.0.0` })).toBeVisible();
  await expect(drawer.getByRole("combobox", { name: "Environment" })).toHaveValue(envName);
  await expect(drawer.getByRole("combobox", { name: "Dataset" })).toHaveValue("users");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && /\/try\/sql$/.test(r.url()) && r.ok()),
    drawer.getByRole("button", { name: "Run" }).click(),
  ]);
  await expect(drawer.getByText('SELECT * FROM "users" LIMIT 50')).toBeVisible();
  const table = drawer.getByRole("table", { name: "Sample rows" });
  await expect(table).toContainText("admin@covenant.local");
  await expect(table.getByRole("columnheader", { name: /^email/ })).toBeVisible();
  // The declared `bogus` column is nowhere in the table: an error; the table's undeclared columns: warnings.
  await expect(drawer.getByText("COLUMN_MISSING")).toBeVisible();
  await expect(drawer.getByText("COLUMN_EXTRA").first()).toBeVisible();
  await drawer.getByRole("button", { name: "Close" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Teardown: the draft contract, then the environment and the registries.
  await page.getByRole("link", { name: "Back to the contract" }).click();
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Delete" }).click();
  await confirmDelete(page, /\/api\/v1\/contracts\/\d+$/);
  await expect(page).toHaveURL(/\/contracts$/);
  await deleteRegistryRow(page, "/environments", envName, /\/api\/v1\/environments\/\d+$/);
  await deleteRegistryRow(page, "/systems", systemName, /\/api\/v1\/systems\/\d+$/);
  await deleteRegistryRow(page, "/domains", domainName, /\/api\/v1\/domains\/\d+$/);
  await deleteRegistryRow(page, "/teams", teamName, /\/api\/v1\/teams\/\d+$/);
});
