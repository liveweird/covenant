import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, type SeededContract, teardownSeededContract, test } from "./helpers";

// Owns only the e2e-lines fixture. The API prepares history; the browser exercises every
// new user action. Published fixture versions are retired before normal API cleanup.
test.describe.serial("parallel release lines", () => {
  let api: APIRequestContext;
  let seeded: SeededContract;
  const contractPath = () => `/contracts/${seeded.contractId}`;
  const versionsPath = () => `/api/v1/contracts/${seeded.contractId}/versions`;
  const line = (page: Page, major: number) => page.getByRole("region", { name: `Release line ${major}.x`, exact: true });

  async function transition(versionId: number, to: string) {
    const response = await api.post(`${versionsPath()}/${versionId}/transition`, { data: { to } });
    expect(response.ok(), await response.text()).toBeTruthy();
  }

  async function addPublished(version: string, deprecated = false) {
    const response = await api.post(versionsPath(), { data: { version, content: PETSTORE(seeded.contractName, version) } });
    expect(response.status(), await response.text()).toBe(201);
    const { id } = await response.json() as { id: number };
    await transition(id, "PROPOSED");
    await transition(id, "ACTIVE");
    if (deprecated) await transition(id, "DEPRECATED");
  }

  async function waitForSuccess(page: Page, message: string) {
    const notice = page.getByRole("alert").filter({ hasText: message });
    await expect(notice).toBeVisible();
    // Success notices pause while hovered. Move off the action area before waiting for
    // dismissal, so the next lifecycle button is not covered by the previous notice.
    await page.mouse.move(0, 0);
    await expect(notice).toHaveCount(0);
  }

  test.beforeAll(async () => {
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-lines", PETSTORE("Release lines", "1.0.0"));
    await addPublished("1.9.0", true);
    await addPublished("1.10.0");
    await addPublished("2.0.0");
  });

  test.afterAll(async () => {
    if (!api) return;
    try {
      if (seeded) {
        const response = await api.get(`${versionsPath()}?pageSize=100`);
        expect(response.ok(), await response.text()).toBeTruthy();
        const { items } = await response.json() as { items: { id: number; lifecycle: string }[] };
        for (const version of items) {
          if (version.lifecycle === "ACTIVE") await transition(version.id, "DEPRECATED");
          if (version.lifecycle === "ACTIVE" || version.lifecycle === "DEPRECATED") await transition(version.id, "RETIRED");
        }
        await teardownSeededContract(api, seeded);
      }
    } finally {
      await api.dispose();
    }
  });

  test("an owner publishes a maintenance backport while a newer major remains active", async ({ page }) => {
    await login(page);
    await page.goto(contractPath());
    await expect(line(page, 1).getByRole("link", { name: "1.10.0", exact: true }).first()).toBeVisible();
    await expect(line(page, 2).getByRole("link", { name: "2.0.0", exact: true }).first()).toBeVisible();
    await page.getByRole("link", { name: "New version in 1.x", exact: true }).click();
    await expect(page.getByRole("heading", { name: `New version of ${seeded.contractName}` })).toBeVisible();
    await page.getByLabel("Version", { exact: true }).fill("1.9.1");
    const editor = page.getByRole("textbox", { name: "Contract document" });
    await editor.click();
    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.insertText(PETSTORE(seeded.contractName, "1.9.1"));
    await expect(page.getByRole("region", { name: "Findings", exact: true })).toContainText("Compared against published version 1.9.0");
    await page.getByRole("button", { name: "Save draft", exact: true }).click();
    await expect(page.getByRole("button", { name: "Propose", exact: true })).toBeVisible();
    await waitForSuccess(page, "Version created");
    await page.getByRole("button", { name: "Propose", exact: true }).click();
    await waitForSuccess(page, "Lifecycle updated");
    await page.getByRole("button", { name: "Activate", exact: true }).click();
    await waitForSuccess(page, "Lifecycle updated");
    await expect(page.getByRole("button", { name: "Deprecate", exact: true })).toBeVisible();
    await page.getByRole("link", { name: "Back to the contract", exact: true }).click();
    await expect(line(page, 1).getByRole("link", { name: "1.10.0", exact: true }).first()).toBeVisible();
    await expect(line(page, 2).getByRole("link", { name: "2.0.0", exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Filter versions in 1.x", exact: true }).click();
    const table = page.getByRole("table", { name: `Versions of ${seeded.contractName}` });
    await expect(table.getByRole("link", { name: "Open version 1.9.1", exact: true })).toBeVisible();
    await expect(table.getByRole("link", { name: "Open version 2.0.0", exact: true })).toHaveCount(0);
  });

  test("an owner manages support and a recommendation independently for each release line", async ({ page }, testInfo) => {
    await login(page);
    await page.goto(contractPath());
    await page.getByRole("button", { name: "Edit policy for 1.x", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Edit policy for 1.x", exact: true });
    await dialog.getByRole("combobox", { name: "Support status", exact: true }).click();
    await page.getByRole("option", { name: "Maintenance", exact: true }).click();
    await dialog.getByLabel("Support ends on", { exact: true }).fill("2027-12-31");
    await dialog.getByLabel("Support policy", { exact: true }).fill("Security fixes and compatible backports for existing consumers.");
    await dialog.getByRole("combobox", { name: "Recommended version", exact: true }).click();
    await page.getByRole("option", { name: "1.9.1", exact: true }).click();
    await dialog.getByRole("button", { name: "Save policy", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await page.reload();
    await expect(line(page, 1)).toContainText("Maintenance");
    await expect(line(page, 1)).toContainText("Security fixes and compatible backports for existing consumers.");
    await expect(line(page, 1).getByRole("link", { name: "1.9.1", exact: true })).toBeVisible();
    await expect(line(page, 2).getByRole("link", { name: "2.0.0", exact: true }).first()).toBeVisible();
    await expect(page.getByRole("region", { name: "History", exact: true })).toContainText("1.x");
    const scan = await new AxeBuilder({ page }).include('[aria-label="Release lines"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    const screenshot = await page.screenshot({ fullPage: true });
    await testInfo.attach("Release lines and support policy", { body: screenshot, contentType: "image/png" });
  });

  test("ending release line support does not retire its contract versions", async ({ page }) => {
    await login(page);
    await page.goto(contractPath());
    await page.getByRole("button", { name: "Edit policy for 1.x", exact: true }).click();
    const dialog = page.getByRole("dialog", { name: "Edit policy for 1.x", exact: true });
    await dialog.getByRole("combobox", { name: "Support status", exact: true }).click();
    await page.getByRole("option", { name: "End of life", exact: true }).click();
    await dialog.getByRole("button", { name: "Save policy", exact: true }).click();
    await expect(dialog).toHaveCount(0);
    await expect(line(page, 1)).toContainText("No recommended version");
    await expect(line(page, 2).getByRole("link", { name: "2.0.0", exact: true }).first()).toBeVisible();
    await page.getByRole("link", { name: "Open version 1.9.1", exact: true }).click();
    await expect(page.getByRole("button", { name: "Deprecate", exact: true })).toBeVisible();
  });
});
