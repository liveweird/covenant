// The Domain → System registries: an admin creates a domain, a system inside it, moves the
// system, sees the holds-systems refusal, then cleans up; a regular user sees read-only lists.
// Owns: its throwaway domains/systems (unique `e2e-dom-*` / `e2e-sys-*` names) and user.
import { createUserViaUi, deleteUserRow, expect, login, openFilters, rowOperation, signOut, test, uniqueText } from "./helpers";

async function confirmDelete(page: Parameters<typeof login>[0], urlPattern: RegExp) {
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "DELETE" && urlPattern.test(r.url()) && r.ok()),
    page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click(),
  ]);
}

test("admin curates a domain and a system, moves the system, and hits the holds-systems refusal", async ({ page }) => {
  await login(page);
  const domainA = uniqueText("e2e-dom-a");
  const domainB = uniqueText("e2e-dom-b");
  const systemName = uniqueText("e2e-sys");

  await page.goto("/domains");
  await expect(page.getByRole("heading", { name: "Domains" })).toBeVisible();
  for (const name of [domainA, domainB]) {
    await page.getByRole("button", { name: "New domain" }).click();
    await page.getByRole("dialog").getByLabel("Name").fill(name);
    await Promise.all([
      page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/domains") && r.ok()),
      page.getByRole("dialog").getByRole("button", { name: "Create", exact: true }).click(),
    ]);
  }
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(domainA);
  await expect(page.getByRole("cell", { name: domainA, exact: true })).toBeVisible();

  // A system inside domain A.
  await page.goto("/systems");
  await expect(page.getByRole("heading", { name: "Systems" })).toBeVisible();
  await page.getByRole("button", { name: "New system" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("combobox", { name: "Domain" }).click();
  await dialog.getByRole("combobox", { name: "Domain" }).fill(domainA);
  await page.getByRole("option", { name: domainA }).click();
  await dialog.getByLabel("Name").fill(systemName);
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "POST" && r.url().endsWith("/api/v1/systems") && r.ok()),
    dialog.getByRole("button", { name: "Create", exact: true }).click(),
  ]);
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(systemName);
  const row = page.getByRole("row").filter({ hasText: systemName });
  await expect(row.getByRole("cell", { name: domainA, exact: true })).toBeVisible();

  // The domain refuses deletion while it holds the system.
  await page.goto("/domains");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(domainA);
  await rowOperation(page, domainA, `Delete ${domainA}`);
  await page.getByRole("dialog").getByRole("button", { name: "Delete", exact: true }).click();
  await expect(page.getByText("This domain still holds systems — move or delete them first.")).toBeVisible();
  await page.getByRole("dialog").getByRole("button", { name: "Cancel" }).click();

  // Move the system to domain B; then A is deletable.
  await page.goto("/systems");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(systemName);
  await rowOperation(page, systemName, `Edit ${systemName}`);
  const edit = page.getByRole("dialog");
  await edit.getByRole("combobox", { name: "Domain" }).click();
  await edit.getByRole("combobox", { name: "Domain" }).fill(domainB);
  await page.getByRole("option", { name: domainB }).click();
  await Promise.all([
    page.waitForResponse((r) => r.request().method() === "PUT" && /\/api\/v1\/systems\/\d+$/.test(r.url()) && r.ok()),
    edit.getByRole("button", { name: "Save", exact: true }).click(),
  ]);
  await expect(page.getByRole("row").filter({ hasText: systemName }).getByRole("cell", { name: domainB, exact: true })).toBeVisible();

  await page.goto("/domains");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(domainA);
  await rowOperation(page, domainA, `Delete ${domainA}`);
  await confirmDelete(page, /\/api\/v1\/domains\/\d+$/);
  await expect(page.getByRole("cell", { name: domainA, exact: true })).toHaveCount(0);

  // Cleanup: the system, then domain B.
  await page.goto("/systems");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(systemName);
  await rowOperation(page, systemName, `Delete ${systemName}`);
  await confirmDelete(page, /\/api\/v1\/systems\/\d+$/);
  await page.goto("/domains");
  await openFilters(page);
  await page.getByLabel("Name", { exact: true }).fill(domainB);
  await rowOperation(page, domainB, `Delete ${domainB}`);
  await confirmDelete(page, /\/api\/v1\/domains\/\d+$/);
});

test("a regular user sees the read-only registries", async ({ page }) => {
  await login(page);
  const user = await createUserViaUi(page, "E2E Registry Reader");
  await signOut(page);
  await login(page, user.email, user.password);
  await page.goto("/domains");
  await expect(page.getByRole("heading", { name: "Domains" })).toBeVisible();
  await expect(page.getByRole("button", { name: "New domain" })).toHaveCount(0);
  await page.goto("/systems");
  await expect(page.getByRole("heading", { name: "Systems" })).toBeVisible();
  await expect(page.getByRole("button", { name: "New system" })).toHaveCount(0);
  await signOut(page);
  await login(page);
  await deleteUserRow(page, user.name);
});
