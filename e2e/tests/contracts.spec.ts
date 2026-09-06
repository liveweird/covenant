// The contract catalog's core loop: an admin builds the hierarchy (domain, system, team),
// imports an OpenAPI document as a contract's first version, adds a minor version with a
// semantic error through Save-anyway, compares the two, publishes the first (DRAFT → PROPOSED →
// ACTIVE — the text locks), sees a breaking change against it flagged until the MAJOR bump,
// downloads it, reads the history, and tears everything down through the lifecycle's exit (deprecate → retire) and
// the delete rules — as three serial tests sharing the contract (the per-test budget). A fourth
// test pins the read-only view of a regular user. Owns: its throwaway domain/system/team/contract/user
// (unique `e2e-*` names).
import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { BROKEN_REF, createUserViaUi, deleteUserRow, expect, login, openFilters, PETSTORE, rowOperation, signOut, test, uniqueText } from "./helpers";

/** The required `Pet.name` gone from the response schema — a breaking change against an ACTIVE version. */
const NARROWED = (title: string, version: string) => PETSTORE(title, version).replace("        name:\n          type: string\n", "");

/** CodeMirror's content is a contenteditable textbox named by the editor's aria-label. */
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

// One journey in three tests (Playwright's per-test budget is 60 s; the whole loop — three checker
// round trips, an axe scan, a reload, a download and six lifecycle confirms — needed ~90). Serial:
// each test resumes on the contract page the first one recorded; a failure skips the rest.
test.describe.serial("the contract core loop", () => {
  const domainName = uniqueText("e2e-dom");
  const systemName = uniqueText("e2e-sys");
  const teamName = uniqueText("e2e-team");
  const contractName = uniqueText("e2e-petstore");
  let contractPath = "";

  test("admin imports a contract and iterates a version through Save-anyway and compare", async ({ page }) => {
  await login(page);
  await createDomainAndSystem(page, domainName, systemName);
  await createTeam(page, teamName);

  // Import: the pasted document prefills the name (its title) and the version it declares.
  await page.goto("/contracts/import");
  await expect(page.getByRole("heading", { name: "Import a document" })).toBeVisible();
  await fillEditor(page, PETSTORE(contractName, "1.0.0"));
  await expect(page.getByLabel("Name", { exact: true })).toHaveValue(contractName);
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("1.0.0");
  await expect(page.getByText("No findings — the document passes every check")).toBeVisible();
  await pickOption(page, "System", systemName);
  await pickOption(page, "Owner", teamName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts/import") && r.ok()),
    page.getByRole("button", { name: "Import", exact: true }).click(),
  ]);
  await expect(page.getByText("Contract created with its first version")).toBeVisible();
  await page.getByRole("link", { name: "Open the version" }).click();
  await expect(page.getByRole("heading", { name: `${contractName} 1.0.0` })).toBeVisible();
  await expect(page.getByRole("textbox", { name: "Contract document" })).toContainText("listPets");

  // A minor version from the first, with a broken $ref: the strict save asks for the waiver.
  await page.getByRole("link", { name: "Back to the contract" }).click();
  await expect(page.getByRole("heading", { name: contractName })).toBeVisible();
  contractPath = new URL(page.url()).pathname;
  await page.getByRole("link", { name: "New version" }).click();
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("1.0.1");
  await page.getByRole("button", { name: "Minor" }).click();
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("1.1.0");
  await fillEditor(page, BROKEN_REF(contractName, "1.1.0"));
  await expect(page.getByRole("list", { name: "Findings" })).toContainText("Missing");
  await page.getByRole("button", { name: "Save draft" }).click();
  const waiver = page.getByRole("dialog");
  await expect(waiver.getByText(/blocking finding/)).toBeVisible();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().includes("allowInvalid=true") && r.ok()),
    waiver.getByRole("button", { name: "Save anyway" }).click(),
  ]);
  await expect(page.getByRole("heading", { name: `${contractName} 1.1.0` })).toBeVisible();
  // swagger-parser and Spectral both flag the missing schema — the stored report carries their errors.
  await expect(page.getByRole("region", { name: "Findings" })).toContainText(" errors");
  await expect(page.getByRole("region", { name: "Findings" })).not.toContainText("0 errors");

  // The diff between the two: the changed $ref shows as a −/+ pair.
  await page.getByRole("link", { name: "Back to the contract" }).click();
  await page.getByRole("link", { name: "Compare versions" }).click();
  const diff = page.getByRole("group", { name: "Differences from 1.0.0 to 1.1.0" });
  await expect(diff).toBeVisible();
  await expect(diff).toContainText('+                   $ref: "#/components/schemas/Missing"');
  await expect(page.getByText("+2 / −2 lines")).toBeVisible();
  });

  test("admin publishes the version, reads it in the reader, downloads it and sees the breaking-change gate", async ({ page }) => {
  await login(page);
  await page.goto(contractPath);
  await expect(page.getByRole("heading", { name: contractName })).toBeVisible();

  // Publish 1.0.0: DRAFT → PROPOSED → ACTIVE locks the text; the download carries the server's file name.
  await page.getByRole("link", { name: "Open version 1.0.0" }).click();
  await expect(page.getByRole("button", { name: "Edit document" })).toBeVisible();
  await page.getByRole("button", { name: "Propose" }).click();
  await expect(page.getByRole("button", { name: "Activate" })).toBeVisible();
  await page.getByRole("button", { name: "Activate" }).click();
  await expect(page.getByRole("button", { name: "Deprecate" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Edit document" })).toHaveCount(0);

  // The Reader: a published version opens on it (the page stays on Source while it is edited in
  // place — the default is frozen at load), so switch over: the operation card, an axe scan of the
  // settled reader, then Source again — the choice survives a reload.
  const viewToggle = page.getByRole("radiogroup", { name: "Document view" });
  await viewToggle.getByText("Reader", { exact: true }).click();
  const reader = page.getByRole("region", { name: "Contract reader" });
  await expect(reader.getByRole("heading", { name: "/pets" })).toBeVisible();
  await expect(reader.getByRole("button", { name: "Pet" })).toBeVisible();
  const axe = await new AxeBuilder({ page }).include('[aria-label="Contract reader"]').withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(axe.violations.map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(" ")) }))).toEqual([]);
  await page.reload();
  await expect(page.getByRole("region", { name: "Contract reader" })).toBeVisible();
  await viewToggle.getByText("Source", { exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Contract document" })).toBeVisible();
  await page.getByRole("button", { name: "More actions" }).click();
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("menuitem", { name: "Download" }).click()]);
  expect(download.suggestedFilename().endsWith(`__${contractName}__1.0.0.yaml`)).toBe(true);

  // A breaking change against the ACTIVE 1.0.0: the live check compares against it and blocks
  // the minor bump with BREAKING_WITHOUT_MAJOR_BUMP; the Major bump turns the facts into notes.
  await page.getByRole("link", { name: "Back to the contract" }).click();
  await page.getByRole("link", { name: "New version" }).click();
  await fillEditor(page, NARROWED(contractName, "1.2.0"));
  await page.getByRole("button", { name: "Minor" }).click();
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("1.2.0");
  const findings = page.getByRole("region", { name: "Findings" });
  await expect(findings).toContainText("Compared against active version 1.0.0 for breaking changes");
  await expect(findings).toContainText("BREAKING_WITHOUT_MAJOR_BUMP");
  await expect(findings).toContainText("CHANGED_RESPONSE");
  await page.getByRole("button", { name: "Major" }).click();
  await expect(page.getByLabel("Version", { exact: true })).toHaveValue("2.0.0");
  await expect(findings).not.toContainText("BREAKING_WITHOUT_MAJOR_BUMP");
  await expect(findings).toContainText("CHANGED_RESPONSE");
  await page.getByRole("link", { name: "Back to the contract" }).click();

  // The history: every step so far is a localized line, newest first.
  const history = page.getByRole("region", { name: "History" });
  await expect(history).toContainText("Version 1.0.0: Proposed → Active");
  await expect(history).toContainText("Version 1.1.0 created");
  await expect(history).toContainText("Version 1.0.0 imported");
  });

  test("admin retires the version and deletes the contract and its registries", async ({ page }) => {
  await login(page);
  await page.goto(contractPath);
  await expect(page.getByRole("heading", { name: contractName })).toBeVisible();

  // Teardown through the rules: the draft deletes; the contract refuses while 1.0.0 is active.
  await rowOperation(page, "1.1.0", "Delete version 1.1.0");
  await confirmDelete(page, /\/versions\/\d+$/);
  await expect(page.getByRole("link", { name: "Open version 1.1.0" })).toHaveCount(0);
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Delete" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();
  await expect(page.getByText(/still has active or deprecated versions/)).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();
  await page.getByRole("link", { name: "Open version 1.0.0" }).click();
  await page.getByRole("button", { name: "Deprecate" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Deprecate" }).click();
  await expect(page.getByRole("button", { name: "Retire" })).toBeVisible();
  await page.getByRole("button", { name: "Retire" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Retire" }).click();
  await expect(page.getByRole("button", { name: "Retire" })).toHaveCount(0);
  await page.getByRole("link", { name: "Back to the contract" }).click();
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Delete" }).click();
  await confirmDelete(page, /\/api\/v1\/contracts\/\d+$/);
  await expect(page).toHaveURL(/\/contracts$/);
  await deleteRegistryRow(page, "/systems", systemName, /\/api\/v1\/systems\/\d+$/);
  await deleteRegistryRow(page, "/domains", domainName, /\/api\/v1\/domains\/\d+$/);
  await deleteRegistryRow(page, "/teams", teamName, /\/api\/v1\/teams\/\d+$/);
  });
});

test("a regular user reads a contract in the hierarchy and the list but gets no write actions", async ({ page }) => {
  await login(page);
  const domainName = uniqueText("e2e-dom-r");
  const systemName = uniqueText("e2e-sys-r");
  const teamName = uniqueText("e2e-team-r");
  const contractName = uniqueText("e2e-readonly");
  const reader = await createUserViaUi(page, "E2E Contract Reader");
  await createDomainAndSystem(page, domainName, systemName);
  await createTeam(page, teamName);

  await page.goto("/contracts/new");
  await pickOption(page, "System", systemName);
  await page.getByLabel("Name", { exact: true }).fill(contractName);
  await pickOption(page, "Owner", teamName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/contracts") && r.ok()),
    page.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await expect(page.getByRole("heading", { name: contractName })).toBeVisible();
  await expect(page.getByRole("link", { name: "New version" })).toBeVisible();

  await signOut(page);
  await login(page, reader.email, reader.password);
  await expect(page.getByRole("heading", { name: "Hierarchy" })).toBeVisible();
  await openFilters(page);
  await page.getByLabel("Search", { exact: true }).fill(contractName);
  await expect(page.getByRole("link", { name: `Open contract ${contractName}` })).toBeVisible();
  await page.getByRole("link", { name: `Open contract ${contractName}` }).click();
  await expect(page.getByRole("heading", { name: contractName })).toBeVisible();
  await expect(page.getByRole("link", { name: "New version" })).toHaveCount(0);
  await page.getByRole("button", { name: "More actions" }).click();
  await expect(page.getByRole("menuitem", { name: "Export (JSON)" })).toBeVisible();
  await expect(page.getByRole("menuitem", { name: "Delete" })).toHaveCount(0);
  await page.keyboard.press("Escape");
  await page.goto("/contracts");
  await openFilters(page);
  await page.getByLabel("Search", { exact: true }).fill(contractName);
  await expect(page.getByRole("link", { name: `Open contract ${contractName}` })).toBeVisible();
  await expect(page.getByRole("button", { name: `Operations for ${contractName}` })).toHaveCount(0);

  await signOut(page);
  await login(page);
  await page.goto("/contracts");
  await openFilters(page);
  await page.getByLabel("Search", { exact: true }).fill(contractName);
  await rowOperation(page, contractName, `Delete ${contractName}`);
  await confirmDelete(page, /\/api\/v1\/contracts\/\d+$/);
  await deleteRegistryRow(page, "/systems", systemName, /\/api\/v1\/systems\/\d+$/);
  await deleteRegistryRow(page, "/domains", domainName, /\/api\/v1\/domains\/\d+$/);
  await deleteRegistryRow(page, "/teams", teamName, /\/api\/v1\/teams\/\d+$/);
  await deleteUserRow(page, reader.name);
});
