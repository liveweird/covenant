import { type APIRequestContext, type APIResponse, expect, type Page, request as playwrightRequest, test } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { BASE_URL } from "../playwright.config";

export { expect, test };

/** The seeded bootstrap admin (V3) — the compose demo leaves its password unrotated. */
export const ADMIN = "admin@covenant.local";
const PASSWORD = "changeme";

/**
 * Navigate to a usable sign-in form. Any leftover session has to go first: while one exists
 * the app's RedirectIfAuthed bounces /login to the home page, so the form never renders and
 * a fill() waits out the whole test timeout.
 */
async function gotoSignInForm(page: Page): Promise<void> {
  if (!page.url().startsWith("http")) await page.goto("/login");
  await page.evaluate(() => localStorage.clear());
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
}

/** Sign in through the real login form. */
export async function login(page: Page, email = ADMIN, password = PASSWORD): Promise<void> {
  await gotoSignInForm(page);
  // Target by textbox role: getByLabel("Password") also matches the visibility-toggle button.
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  await page.getByRole("textbox", { name: "Password" }).fill(password);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(accountMenu(page)).toBeVisible({ timeout: 15_000 });
}

/** The header account-menu trigger — visible only inside the authenticated shell (v1.19.0). */
export function accountMenu(page: Page) {
  return page.getByRole("button", { name: "Account menu" });
}

/** Sign out through the account menu (the former header Logout button). */
export async function signOut(page: Page): Promise<void> {
  await accountMenu(page).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
}

/**
 * Delete a user from the Users list through its row menu (v1.19.0: the row actions sit under
 * an "Operations for <name>" kebab) — the cleanup step every throwaway-user spec ends with.
 * Filters the list to the name first, then waits for the DELETE to land.
 */
export async function deleteUserRow(page: Page, name: string): Promise<void> {
  await page.goto("/users");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(name);
  await rowOperation(page, name, `Delete ${name}`);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

/**
 * Create a throwaway user through the real UI (an admin must be signed in) and capture the
 * generated password from the one-time reveal modal. Never mutate seeded accounts — use this.
 * The id comes from the POST response; the password from the dialog after "Show password".
 */
export async function createUserViaUi(
  page: Page,
  namePrefix = "E2E User",
): Promise<{ id: number; name: string; email: string; password: string }> {
  const name = uniqueText(namePrefix);
  const email = `${name.toLowerCase().replace(/[^a-z0-9-]/g, "-")}@covenant.local`;
  await page.goto("/users/new");
  await page.getByRole("textbox", { name: "Name" }).fill(name);
  await page.getByRole("textbox", { name: "Email" }).fill(email);
  const [created] = await Promise.all([
    page.waitForResponse(
      (r) => r.url().endsWith("/api/v1/users") && r.request().method() === "POST" && r.ok(),
    ),
    page.getByRole("button", { name: "Create" }).click(),
  ]);
  const id: number = (await created.json()).id;
  const dialog = page.getByRole("dialog");
  // Masked as "*" until revealed — click the eye toggle first.
  await dialog.getByRole("button", { name: "Show password" }).click();
  const password = (await dialog.locator("code").textContent()) ?? "";
  // Mantine renders both a header X and the footer button named Close.
  await dialog.getByRole("button", { name: "Close", exact: true }).last().click();
  await expect(page).toHaveURL(/\/users$/);
  return { id, name, email, password };
}

/** Collision-free text so specs never depend on absolute counts or clean state. */
export function uniqueText(prefix: string): string {
  return `${prefix}-${randomUUID().slice(0, 8)}`;
}

/** A minimal OpenAPI 3.1 document — the fixture every spec that needs a valid contract document shares. */
export const PETSTORE = (title: string, version: string) => `openapi: 3.1.0
info:
  title: ${title}
  version: ${version}
  description: Playwright's petstore.
  contact:
    name: Platform team
servers:
  - url: https://api.example.com/v1
tags:
  - name: pets
paths:
  /pets:
    get:
      operationId: listPets
      summary: List pets
      description: Returns every pet.
      tags: [pets]
      responses:
        "200":
          description: The pets.
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: "#/components/schemas/Pet"
components:
  schemas:
    Pet:
      type: object
      required: [id, name]
      properties:
        id:
          type: integer
        name:
          type: string
`;

/** A broken internal $ref — swagger-parser reports it as a SEMANTIC error, the soft kind Save-anyway waives. */
export const BROKEN_REF = (title: string, version: string) => PETSTORE(title, version).replace("#/components/schemas/Pet", "#/components/schemas/Missing");

/**
 * Ensure a list view's filter panel is expanded. Idempotent on purpose: the open/collapsed
 * state persists per view in localStorage (covenant.viewSettings.*), so within one test a
 * revisited page restores the panel open — a blind toggle click would close it again.
 */
export async function openFilters(page: Page): Promise<void> {
  const toggle = page.getByRole("button", { name: "Filters" });
  await expect(toggle).toBeVisible();
  if ((await toggle.getAttribute("aria-expanded")) !== "true") {
    await toggle.click();
  }
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
}

/**
 * Drive a list row's action through its "Operations for <name>" kebab menu (the Users rows
 * today; list rows carry interpolated item names, hence the open `string` union member).
 */
export async function rowOperation(
  page: Page,
  name: string,
  operation: "Edit" | "Delete" | (string & {}),
): Promise<void> {
  const trigger = page.getByRole("button", { name: `Operations for ${name}` });
  // A missing row fails HERE with its name, not as an anonymous click timeout at the end of the test budget.
  await expect(trigger).toBeVisible();
  // Ensure THIS row's menu actually opened: a previous row's still-fading dropdown treats
  // the first click as its outside-click and swallows it, leaving the WRONG menu mounted —
  // an unscoped menuitem click would then drive the other row's operation. Bounded: an
  // unbounded toPass() retries until the TEST budget is gone and reports the wrong step.
  await expect(async () => {
    if ((await trigger.getAttribute("aria-expanded")) !== "true") await trigger.click();
    expect(await trigger.getAttribute("aria-expanded")).toBe("true");
  }).toPass({ timeout: 10_000 });
  const dropdownId = await trigger.getAttribute("aria-controls");
  await page.locator(`[id="${dropdownId}"]`).getByRole("menuitem", { name: operation }).click();
}

/**
 * Register a PostgreSQL environment (the stack's own database, `covenant`/`covenant`) on an
 * existing system through the Environments page — the "New environment" modal, HTTP unchecked,
 * PostgreSQL checked (`tryit.spec.ts`'s original journey, lifted here once `infer.spec.ts` needed
 * the same fixture). Returns the created environment's id for callers that tear it down by id.
 */
export async function createPostgresEnvironment(page: Page, domainName: string, systemName: string, envName: string): Promise<number> {
  await page.goto("/environments");
  await page.getByRole("button", { name: "New environment" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("combobox", { name: "System" }).click();
  await dialog.getByRole("combobox", { name: "System" }).fill(systemName);
  await page.getByRole("option", { name: `${domainName} / ${systemName}` }).click();
  await dialog.getByLabel("Name", { exact: true }).fill(envName);
  await dialog.getByLabel("HTTP target (OpenAPI)").uncheck();
  await dialog.getByLabel("PostgreSQL target (ODCS)").check();
  await dialog.getByLabel("JDBC URL").fill("jdbc:postgresql://postgres:5432/covenant");
  await dialog.getByLabel("Username").fill("covenant");
  await dialog.getByRole("textbox", { name: "Password" }).fill("covenant");
  const [created] = await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/environments") && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await expect(page.getByRole("row", { name: new RegExp(envName) })).toContainText("PostgreSQL");
  return ((await created.json()) as { id: number }).id;
}

/**
 * API-side seeding for specs whose subject is a PAGE, not the journey that creates its data (the
 * accessibility sweep): one admin-authenticated request context against the same stack. Every id
 * it creates is the caller's to delete (`teardownSeededContract`) — the Owns rule applies unchanged.
 */
export async function apiAsAdmin(): Promise<{ api: APIRequestContext; userId: number }> {
  const anonymous = await playwrightRequest.newContext({ baseURL: BASE_URL });
  const login = await anonymous.post("/api/v1/login", { data: { email: ADMIN, password: PASSWORD } });
  expect(login.ok(), await login.text()).toBeTruthy();
  const { token, userId } = (await login.json()) as { token: string; userId: number };
  await anonymous.dispose();
  const api = await playwrightRequest.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
  return { api, userId };
}

export interface SeededContract {
  domainId: number;
  systemId: number;
  teamId: number;
  teamName: string;
  contractId: number;
  contractName: string;
  versionId: number;
}

async function createdId(response: APIResponse): Promise<number> {
  expect(response.status(), await response.text()).toBe(201);
  return ((await response.json()) as { id: number }).id;
}

/** A domain → system → team-owned OPENAPI contract with one DRAFT version holding `document`, all uniquely named. */
export async function seedContractViaApi(api: APIRequestContext, prefix: string, document: string): Promise<SeededContract> {
  const domainId = await createdId(await api.post("/api/v1/domains", { data: { name: uniqueText(`${prefix}-dom`) } }));
  const systemId = await createdId(await api.post("/api/v1/systems", { data: { domainId, name: uniqueText(`${prefix}-sys`) } }));
  const teamName = uniqueText(`${prefix}-team`);
  const teamId = await createdId(await api.post("/api/v1/teams", { data: { name: teamName } }));
  const contractName = uniqueText(`${prefix}-api`);
  const contractId = await createdId(
    await api.post("/api/v1/contracts", { data: { systemId, type: "OPENAPI", name: contractName, ownerTeamId: teamId } }),
  );
  const versionId = await createdId(
    await api.post(`/api/v1/contracts/${contractId}/versions`, { data: { version: "1.0.0", content: document } }),
  );
  return { domainId, systemId, teamId, teamName, contractId, contractName, versionId };
}

/** The reverse of `seedContractViaApi`, in dependency order; a row already gone (404) is fine. */
export async function teardownSeededContract(api: APIRequestContext, seeded: SeededContract): Promise<void> {
  for (const path of [
    `/api/v1/contracts/${seeded.contractId}`,
    `/api/v1/systems/${seeded.systemId}`,
    `/api/v1/domains/${seeded.domainId}`,
    `/api/v1/teams/${seeded.teamId}`,
  ]) {
    const response = await api.delete(path);
    expect([204, 404], `${path} -> ${response.status()} ${await response.text()}`).toContain(response.status());
  }
}
