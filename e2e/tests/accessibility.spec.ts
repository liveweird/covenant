// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen, the authenticated list
// and form pages, the detail pages of one API-seeded fixture contract, and the overlays (drawers,
// an editor modal) — where focus traps, aria-modal and labels actually live. Owns: the fixture
// contract with its domain/system/team (unique `e2e-axe-*` names), created and deleted via the API.
import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, Locator, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, seedContractViaApi, type SeededContract, teardownSeededContract, test, uniqueText } from "./helpers";

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

// No waivers: the theme's text/dimmed/ink tokens are AA-tested in web/src/theme.test.ts, so
// the color-contrast rule runs for real here (Lettuce's posture, not Toadie's waiver). Fix a
// finding at the token level — never by patching single elements.
async function scan(page: Page, include?: string): Promise<void> {
  const builder = new AxeBuilder({ page }).withTags(AXE_TAGS);
  const results = await (include ? builder.include(include) : builder).analyze();
  // Keep the assert readable on failure: one line per violation with the offending nodes.
  const summary = results.violations.map((v) => ({
    id: v.id,
    impact: v.impact,
    help: v.help,
    nodes: v.nodes.map((n) => n.target.join(" ")),
  }));
  expect(summary).toEqual([]);
}

test("login screen has no WCAG A/AA violations", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await scan(page);
});

// One test per page keeps the report line-per-page.
const AUTHED_PAGES: { path: string; heading: string }[] = [
  { path: "/", heading: "Hierarchy" },
  { path: "/contracts", heading: "Contracts" },
  { path: "/contracts/new", heading: "New contract" },
  { path: "/contracts/import", heading: "Import a document" },
  { path: "/domains", heading: "Domains" },
  { path: "/systems", heading: "Systems" },
  { path: "/environments", heading: "Environments" },
  { path: "/teams", heading: "Teams" },
  { path: "/users", heading: "Users" },
  { path: "/users/new", heading: "New user" },
  { path: "/feature-flags", heading: "Feature flags" },
  { path: "/change-password", heading: "Change password" },
  { path: "/changelog", heading: "Changelog" },
];

for (const { path, heading } of AUTHED_PAGES) {
  test(`${path} has no WCAG A/AA violations`, async ({ page }) => {
    await login(page);
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading })).toBeVisible();
    await scan(page);
  });
}

// Detail pages need a subject: one fixture contract seeded through the API (no UI journey — that is
// contracts.spec.ts's job), scanned in every state a reader or writer reaches, then deleted.
const AXE_PETSTORE = `openapi: 3.1.0
info:
  title: Axe petstore
  version: 1.0.0
  description: The accessibility sweep's fixture.
paths:
  /pets:
    get:
      operationId: listPets
      summary: List pets
      responses:
        '200':
          description: The pets
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

test.describe("detail pages and overlays", () => {
  let api: APIRequestContext;
  let adminId: number;
  let seeded: SeededContract;

  test.beforeAll(async () => {
    ({ api, userId: adminId } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-axe", AXE_PETSTORE);
  });
  test.afterAll(async () => {
    await teardownSeededContract(api, seeded);
    await api.dispose();
  });

  const versionPath = () => `/contracts/${seeded.contractId}/versions/${seeded.versionId}`;
  const DETAIL_PAGES: { name: string; path: () => string; settled: (page: Page) => Locator }[] = [
    { name: "the contract page", path: () => `/contracts/${seeded.contractId}`, settled: (p) => p.getByRole("heading", { name: seeded.contractName }) },
    { name: "the version page in Source view", path: () => `${versionPath()}?view=source`, settled: (p) => p.getByRole("textbox", { name: "Contract document" }) },
    { name: "the version page in Reader view", path: () => `${versionPath()}?view=reader`, settled: (p) => p.getByRole("region", { name: "Contract reader" }).getByRole("heading", { name: "/pets" }) },
    { name: "the edit-contract page", path: () => `/contracts/${seeded.contractId}/edit`, settled: (p) => p.getByRole("heading", { name: `Edit ${seeded.contractName}` }) },
    { name: "the new-version page", path: () => `/contracts/${seeded.contractId}/versions/new`, settled: (p) => p.getByRole("heading", { name: `New version of ${seeded.contractName}` }) },
    { name: "the compare page", path: () => `/contracts/${seeded.contractId}/diff`, settled: (p) => p.getByRole("heading", { name: `Compare versions of ${seeded.contractName}` }) },
    { name: "the team page", path: () => `/teams/${seeded.teamId}`, settled: (p) => p.getByRole("heading", { name: seeded.teamName }) },
    { name: "the edit-user page", path: () => `/users/${adminId}/edit`, settled: (p) => p.getByRole("heading", { name: "Edit user" }) },
    { name: "the user-features page", path: () => `/users/${adminId}/features`, settled: (p) => p.getByRole("heading", { name: "Feature flags" }) },
  ];
  for (const { name, path, settled } of DETAIL_PAGES) {
    test(`${name} has no WCAG A/AA violations`, async ({ page }) => {
      await login(page);
      await page.goto(path());
      await expect(settled(page)).toBeVisible();
      await scan(page);
    });
  }

  test("the reset-password page has no WCAG A/AA violations", async ({ page }) => {
    await page.goto("/reset-password");
    await expect(page.getByRole("heading", { name: "Reset password" })).toBeVisible();
    await scan(page);
  });

  test("the not-found page has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(`/${uniqueText("nowhere")}`);
    await expect(page.getByRole("heading").first()).toBeVisible();
    await scan(page);
  });

  // Overlays are scanned scoped to the dialog: the page behind them is inert, and the dialog is
  // where a missing label, a broken focus trap or a contrast slip would hide from the page sweep.
  // `toBeVisible` passes mid-transition; axe measures contrast against the fade-in's partial
  // opacity, so the scan waits for the dialog to settle at full opacity.
  async function settledDialog(page: Page): Promise<Locator> {
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveCSS("opacity", "1");
    return dialog;
  }

  test("the notifications drawer has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.getByRole("button", { name: /^Notifications \(/ }).click();
    await settledDialog(page);
    await scan(page, '[role="dialog"]');
  });

  test("the try-it drawer has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto(versionPath());
    await page.getByRole("button", { name: "Try it" }).click();
    const drawer = await settledDialog(page);
    await expect(drawer.getByRole("heading").first()).toBeVisible();
    await scan(page, '[role="dialog"]');
  });

  test("a registry editor modal has no WCAG A/AA violations", async ({ page }) => {
    await login(page);
    await page.goto("/domains");
    await page.getByRole("button", { name: "New domain" }).click();
    const modal = await settledDialog(page);
    await expect(modal.getByRole("heading", { name: "New domain" })).toBeVisible();
    await scan(page, '[role="dialog"]');
  });
});
