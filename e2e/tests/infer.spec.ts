// Contract inference: an HTTP exchange pasted by hand becomes an OpenAPI draft (the templated
// path and the merge note reviewed before saving), and a PostgreSQL table described live through
// an environment becomes an ODCS draft — both then hand off to the ordinary editor and are saved
// through the ordinary Import flow, since the inference feature itself stores nothing. Owns: its
// throwaway domains/systems/teams/environment and contracts (unique `e2e-infer-*` names), all
// deleted by the end.
import type { APIRequestContext, APIResponse, Page } from "@playwright/test";
import { apiAsAdmin, createPostgresEnvironment, expect, login, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";

async function createdId(response: APIResponse): Promise<number> {
  expect(response.status(), await response.text()).toBe(201);
  return ((await response.json()) as { id: number }).id;
}

/** A throwaway domain → system → team trio via the API — the ownership pickers Import needs, without seeding a contract. */
async function seedRegistries(api: APIRequestContext, prefix: string) {
  const domainName = uniqueText(`${prefix}-dom`);
  const domainId = await createdId(await api.post("/api/v1/domains", { data: { name: domainName } }));
  const systemName = uniqueText(`${prefix}-sys`);
  const systemId = await createdId(await api.post("/api/v1/systems", { data: { domainId, name: systemName } }));
  const teamName = uniqueText(`${prefix}-team`);
  const teamId = await createdId(await api.post("/api/v1/teams", { data: { name: teamName } }));
  return { domainId, domainName, systemId, systemName, teamId, teamName };
}

/** CodeMirror's editable content is a contenteditable textbox named by the editor's aria-label (contracts.spec.ts's idiom). */
async function fillCodeEditor(page: Page, label: string, text: string) {
  const editor = page.getByRole("textbox", { name: label });
  await editor.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(text);
}

/** Choose an option from a Mantine Select regardless of whether it is searchable. */
async function selectOption(page: Page, label: string, optionName: string) {
  await page.getByRole("combobox", { name: label }).click();
  await page.getByRole("option", { name: optionName, exact: true }).click();
}

/** The Import page's searchable pickers (System, Owner) — contracts.spec.ts's idiom. */
async function pickOption(page: Page, label: string, value: string) {
  const combo = page.getByRole("combobox", { name: label });
  await combo.click();
  await combo.fill(value);
  await page.getByRole("option", { name: value, exact: true }).click();
}

interface ImportedRow {
  contractId: number;
  versionId: number;
  name: string;
}

test("user infers an OpenAPI draft from a pasted exchange and saves it as the contract's first version", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const { domainId, systemId, systemName, teamId, teamName } = await seedRegistries(api, "e2e-infer-http");

  await login(page);
  await page.goto("/contracts/infer");
  await expect(page.getByRole("heading", { name: "Infer a contract" })).toBeVisible();

  // OpenAPI is the type picker's default and the Paste tab is already active.
  await page.getByLabel("URL", { exact: true }).fill("https://api.example.test/orders/42");
  await fillCodeEditor(page, "Response body", '{"id":42,"total":19.5,"createdAt":"2026-09-07T10:00:00Z"}');
  await page.getByRole("button", { name: "Add sample" }).click();
  await expect(page.getByText("1 sample", { exact: true })).toBeVisible();

  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/infer") && r.ok()),
    page.getByRole("button", { name: "Generate draft" }).click(),
  ]);
  const preview = page.locator('.cm-content[aria-label="Inferred document"]');
  await expect(preview).toContainText("/orders/{orderId}");
  await expect(page.getByRole("region", { name: "Findings" })).toContainText("INFER_PATH_TEMPLATED");

  await page.getByRole("button", { name: "Open in editor" }).click();
  await expect(page).toHaveURL(/\/contracts\/import$/);
  await expect(page.getByRole("textbox", { name: "Contract document" })).toContainText("/orders/{orderId}");
  await expect(page.getByRole("combobox", { name: "Type" })).toHaveValue("OpenAPI");
  // The document's own title/version prefill the Name/Version fields — the import page's existing rule.
  const contractName = await page.getByLabel("Name", { exact: true }).inputValue();
  expect(contractName.length).toBeGreaterThan(0);
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("1.0.0");

  await pickOption(page, "System", systemName);
  await pickOption(page, "Owner", teamName);
  const [importResponse] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/import") && r.ok()),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  const row = ((await importResponse.json()) as { results: ImportedRow[] }).results[0];
  await page.getByRole("link", { name: "Open the version" }).click();
  await expect(page.getByRole("heading", { name: `${row.name} 1.0.0` })).toBeVisible();
  await expect(page.getByText("Draft", { exact: true })).toBeVisible();

  const seeded: SeededContract = { domainId, systemId, teamId, teamName, contractId: row.contractId, contractName: row.name, versionId: row.versionId };
  await teardownSeededContract(api, seeded);
  await api.dispose();
});

test("admin describes the users table through an environment and imports the inferred ODCS draft", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const { domainId, domainName, systemId, systemName, teamId, teamName } = await seedRegistries(api, "e2e-infer-sql");
  const envName = uniqueText("e2e-infer-env");

  await login(page);
  const environmentId = await createPostgresEnvironment(page, domainName, systemName, envName);

  await page.goto("/contracts/infer");
  await expect(page.getByRole("heading", { name: "Infer a contract" })).toBeVisible();
  // The SegmentedControl's radio input is visually hidden — click its label text instead
  // (the VersionViewToggle idiom in contracts.spec.ts).
  await page.getByText("ODCS", { exact: true }).click();
  await page.getByRole("tab", { name: "Observe" }).click();

  await selectOption(page, "Environment", `${systemName} / ${envName}`);
  await selectOption(page, "Dataset", "public.users (table)");
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/infer/observe/sql") && r.ok()),
    page.getByRole("button", { name: "Describe" }).click(),
  ]);
  await expect(page.getByText("columns described")).toBeVisible();
  await page.getByRole("button", { name: "Add to samples" }).click();
  await expect(page.getByText("1 sample", { exact: true })).toBeVisible();

  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/infer") && r.ok()),
    page.getByRole("button", { name: "Generate draft" }).click(),
  ]);
  const preview = page.locator('.cm-content[aria-label="Inferred document"]');
  await expect(preview).toContainText("physicalType: table");
  await expect(preview).toContainText("name: email");
  await expect(preview).toContainText("primaryKey: true");

  await page.getByRole("button", { name: "Open in editor" }).click();
  await expect(page).toHaveURL(/\/contracts\/import$/);
  await expect(page.getByRole("combobox", { name: "Type" })).toHaveValue("ODCS");

  await pickOption(page, "System", systemName);
  await pickOption(page, "Owner", teamName);
  const [importResponse] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/import") && r.ok()),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  const row = ((await importResponse.json()) as { results: ImportedRow[] }).results[0];
  await page.getByRole("link", { name: "Open the version" }).click();
  await expect(page.getByRole("heading", { name: `${row.name} 1.0.0` })).toBeVisible();
  await expect(page.getByText("Draft", { exact: true })).toBeVisible();

  const envDelete = await api.delete(`/api/v1/environments/${environmentId}`);
  expect([204, 404], `env delete -> ${envDelete.status()} ${await envDelete.text()}`).toContain(envDelete.status());
  const seeded: SeededContract = { domainId, systemId, teamId, teamName, contractId: row.contractId, contractName: row.name, versionId: row.versionId };
  await teardownSeededContract(api, seeded);
  await api.dispose();
});
