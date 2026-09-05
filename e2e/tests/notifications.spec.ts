// Following a contract and hearing about it: the admin sets up a contract, a throwaway user
// follows it, the admin adds a version, and the follower's bell announces it — open lands on
// the version, mark-all clears the badge. Owns: its throwaway user, domain, system, team and
// contract (unique `e2e-*` names), all deleted at the end.
import type { Page } from "@playwright/test";
import { createUserViaUi, deleteUserRow, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

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

const PETSTORE = `openapi: 3.1.0
info:
  title: Followed petstore
  version: 1.0.0
paths:
  /pets:
    get:
      operationId: listPets
      responses:
        "200":
          description: The pets.
`;

test("a follower's bell announces a new version, opens it, and mark-all clears the badge", async ({ page }) => {
  await login(page);
  const domainName = uniqueText("e2e-dom-n");
  const systemName = uniqueText("e2e-sys-n");
  const teamName = uniqueText("e2e-team-n");
  const contractName = uniqueText("e2e-followed");
  const follower = await createUserViaUi(page, "E2E Follower");
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
  const contractUrl = page.url();

  // The follower follows — the button flips and counts them.
  await signOut(page);
  await login(page, follower.email, follower.password);
  await expect(page.getByRole("button", { name: "Notifications (0 unread)" })).toBeVisible();
  await page.goto(contractUrl);
  await page.getByRole("button", { name: "Follow · 0" }).click();
  await expect(page.getByRole("button", { name: "Following · 1" })).toBeVisible();

  // The admin adds a version: the actor hears nothing, the follower hears once.
  await signOut(page);
  await login(page);
  await page.goto(contractUrl);
  await page.getByRole("link", { name: "New version" }).click();
  const editor = page.getByRole("textbox", { name: "Contract document" });
  await editor.click();
  await page.keyboard.press("ControlOrMeta+a");
  await page.keyboard.insertText(PETSTORE);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && /\/versions$/.test(r.url()) && r.ok()),
    page.getByRole("button", { name: "Save draft" }).click(),
  ]);
  await expect(page.getByRole("heading", { name: `${contractName} 1.0.0` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Notifications (0 unread)" })).toBeVisible();

  await signOut(page);
  await login(page, follower.email, follower.password);
  await page.getByRole("button", { name: "Notifications (1 unread)" }).click();
  const dialog = page.getByRole("dialog");
  await expect(dialog.getByRole("listitem")).toContainText(`created version 1.0.0 of ${contractName}`);
  const openButton = dialog.getByRole("button", { name: /^Open the subject of notification/ });
  await openButton.click();
  await expect(page.getByRole("heading", { name: `${contractName} 1.0.0` })).toBeVisible();
  await expect(page.getByRole("button", { name: "Notifications (0 unread)" })).toBeVisible();
  // Un-see it again, then Mark all as seen clears the badge in one click.
  await page.getByRole("button", { name: "Notifications (0 unread)" }).click();
  await page.getByRole("dialog").getByRole("button", { name: /^Mark notification \d+ as unseen/ }).click();
  await expect(page.getByRole("button", { name: "Notifications (1 unread)" })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Mark all as seen" }).click();
  await expect(page.getByRole("button", { name: "Notifications (0 unread)" })).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Close" }).click();

  // Teardown: the admin deletes the draft's contract, the registries and the follower.
  await signOut(page);
  await login(page);
  await page.goto(contractUrl);
  await rowOperation(page, "1.0.0", "Delete version 1.0.0");
  await confirmDelete(page, /\/versions\/\d+$/);
  await page.getByRole("button", { name: "More actions" }).click();
  await page.getByRole("menuitem", { name: "Delete" }).click();
  await confirmDelete(page, /\/api\/v1\/contracts\/\d+$/);
  await deleteRegistryRow(page, "/systems", systemName, /\/api\/v1\/systems\/\d+$/);
  await deleteRegistryRow(page, "/domains", domainName, /\/api\/v1\/domains\/\d+$/);
  await deleteRegistryRow(page, "/teams", teamName, /\/api\/v1\/teams\/\d+$/);
  await page.goto("/users");
  await deleteUserRow(page, follower.name);
});
