// Axe accessibility smoke: WCAG 2.0/2.1 A+AA scans over the login screen and the authenticated
// pages. Strictly read-only (no created state).
import AxeBuilder from "@axe-core/playwright";
import { expect, login, test } from "./helpers";

const AXE_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"];

// No waivers: the theme's text/dimmed/ink tokens are AA-tested in web/src/theme.test.ts, so
// the color-contrast rule runs for real here (Lettuce's posture, not Toadie's waiver). Fix a
// finding at the token level — never by patching single elements.
async function scan(page: Parameters<typeof login>[0]): Promise<void> {
  const results = await new AxeBuilder({ page })
    .withTags(AXE_TAGS)
    .analyze();
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
