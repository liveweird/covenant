import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, APIResponse, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";

// Owns its e2e-reviews contract hierarchy and collaborator only. Published fixture versions
// are retired before deleting the parent through the normal API; existing samples stay intact.
test.describe.serial("optional version reviews", () => {
  let api: APIRequestContext;
  let seeded: SeededContract;
  let reviewerId = 0;
  const reviewerName = uniqueText("e2e-reviewer");
  const reviewerEmail = `${reviewerName}@covenant.local`;
  const reviewerPassword = `Review-${uniqueText("test")}!42`;
  const versionApi = () => `/api/v1/contracts/${seeded.contractId}/versions/${seeded.versionId}`;
  const versionUrl = () => `/contracts/${seeded.contractId}/versions/${seeded.versionId}`;
  const reviews = (page: Page) => page.getByRole("region", { name: "Reviews", exact: true });

  async function ok(response: APIResponse, status: number) {
    expect(response.status(), await response.text()).toBe(status);
  }

  async function openVersion(page: Page) {
    await page.goto(versionUrl());
    await expect(page.getByRole("heading", { name: `${seeded.contractName} 1.0.0`, exact: true })).toBeVisible();
    await expect(reviews(page)).toBeVisible();
  }

  async function waitForNotice(page: Page, message: string) {
    const notice = page.getByRole("alert").filter({ hasText: message });
    await expect(notice).toBeVisible();
    await page.mouse.move(0, 0);
    await expect(notice).toHaveCount(0);
  }

  test.beforeAll(async () => {
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-reviews", PETSTORE("Reviewed API", "1.0.0"));
    const created = await api.post("/api/v1/users", { data: {
      name: reviewerName, email: reviewerEmail, password: reviewerPassword, roles: [],
    } });
    await ok(created, 201);
    reviewerId = ((await created.json()) as { id: number }).id;
  });

  test.afterAll(async () => {
    if (!api) return;
    try {
      if (seeded) {
        const response = await api.get(versionApi());
        if (response.ok()) {
          const { lifecycle } = await response.json() as { lifecycle: string };
          if (lifecycle === "ACTIVE") await ok(await api.post(`${versionApi()}/transition`, { data: { to: "DEPRECATED" } }), 200);
          if (lifecycle === "ACTIVE" || lifecycle === "DEPRECATED") {
            await ok(await api.post(`${versionApi()}/transition`, { data: { to: "RETIRED" } }), 200);
          }
        }
        await teardownSeededContract(api, seeded);
      }
      if (reviewerId) await ok(await api.delete(`/api/v1/users/${reviewerId}`), 204);
    } finally {
      await api.dispose();
    }
  });

  test("collaborators review proposed content and an owner publishes independently of decisions", async ({ page }, testInfo) => {
    await login(page);
    await openVersion(page);
    await expect(reviews(page).getByRole("button", { name: "Request review", exact: true })).toHaveCount(0);
    await page.getByRole("button", { name: "Propose", exact: true }).click();
    await waitForNotice(page, "Lifecycle updated");
    await reviews(page).getByRole("button", { name: "Request review", exact: true }).click();
    await expect(reviews(page).getByText("Open", { exact: true }).first()).toBeVisible();
    await expect(reviews(page).getByRole("button", { name: "Approve", exact: true })).toHaveCount(0);

    await login(page, reviewerEmail, reviewerPassword);
    await openVersion(page);
    await expect(page.getByRole("button", { name: "Edit document", exact: true })).toHaveCount(0);
    await reviews(page).getByRole("textbox", { name: "Review comment", exact: true }).fill("The response shape is clear.");
    await reviews(page).getByRole("button", { name: "Add comment", exact: true }).click();
    await expect(reviews(page).getByText("The response shape is clear.", { exact: true })).toBeVisible();
    await reviews(page).getByRole("button", { name: "Approve", exact: true }).click();
    await expect(reviews(page).getByText("Approved", { exact: true }).first()).toBeVisible();

    await login(page);
    await openVersion(page);
    await page.getByRole("button", { name: "Edit document", exact: true }).click();
    const editor = page.getByRole("textbox", { name: "Contract document", exact: true });
    await editor.click();
    await page.keyboard.press("ControlOrMeta+a");
    await page.keyboard.insertText(PETSTORE("Reviewed API updated", "1.0.0"));
    await page.getByRole("button", { name: "Save", exact: true }).click();
    await waitForNotice(page, "Document saved");
    await expect(reviews(page).getByText("Outdated", { exact: true }).first()).toBeVisible();
    await expect(reviews(page).getByText("The response shape is clear.", { exact: true })).toBeVisible();
    await reviews(page).getByRole("button", { name: "Request review", exact: true }).click();
    await expect(reviews(page).getByText("Open", { exact: true }).first()).toBeVisible();

    await login(page, reviewerEmail, reviewerPassword);
    await openVersion(page);
    await reviews(page).getByRole("textbox", { name: "Review comment", exact: true }).fill("Please clarify the response examples before consumers migrate.");
    await reviews(page).getByRole("button", { name: "Request changes", exact: true }).click();
    await expect(reviews(page).getByText("Changes requested", { exact: true }).first()).toBeVisible();

    await login(page);
    await openVersion(page);
    await page.getByRole("button", { name: "Activate", exact: true }).click();
    await waitForNotice(page, "Lifecycle updated");
    await expect(page.getByRole("button", { name: "Deprecate", exact: true })).toBeVisible();
    await expect(reviews(page).getByText("Closed", { exact: true }).first()).toBeVisible();
    await expect(reviews(page).getByText("Please clarify the response examples before consumers migrate.", { exact: true })).toBeVisible();
    await expect(reviews(page).getByRole("textbox", { name: "Review comment", exact: true })).toHaveCount(0);
    const scan = await new AxeBuilder({ page }).include('[aria-label="Reviews"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Version review history after publication", { body: await reviews(page).screenshot(), contentType: "image/png" });
    await page.setViewportSize({ width: 390, height: 844 });
    const bounds = await reviews(page).boundingBox();
    expect(bounds).not.toBeNull();
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
    await testInfo.attach("Version reviews on mobile", { body: await reviews(page).screenshot(), contentType: "image/png" });
  });

  test("review history remains accessible across rounds and discussion pages", async ({ page }) => {
    const response = await api.post(`/api/v1/contracts/${seeded.contractId}/versions`, {
      data: { version: "2.0.0", content: PETSTORE("Paged reviews", "2.0.0") },
    });
    await ok(response, 201);
    const version = await response.json() as { id: number; contentRevision: number };
    const path = `/api/v1/contracts/${seeded.contractId}/versions/${version.id}`;
    let latestRound = 0;
    for (let i = 0; i < 6; i++) {
      await ok(await api.post(`${path}/transition`, { data: { to: "PROPOSED" } }), 200);
      const requested = await api.post(`${path}/reviews`, { data: { expectedContentRevision: version.contentRevision } });
      await ok(requested, 201);
      latestRound = ((await requested.json()) as { id: number }).id;
      if (i < 5) await ok(await api.post(`${path}/transition`, { data: { to: "DRAFT" } }), 200);
    }
    for (let i = 1; i <= 11; i++) {
      await ok(await api.post(`/api/v1/version-reviews/${latestRound}/entries`, {
        data: { expectedContentRevision: version.contentRevision, kind: "COMMENT", body: `Discussion item ${i}` },
      }), 201);
    }
    await login(page);
    await page.goto(`/contracts/${seeded.contractId}/versions/${version.id}`);
    await expect(reviews(page).getByText("Discussion item 11", { exact: true })).toBeVisible();
    const next = reviews(page).getByRole("button", { name: "Next page", exact: true });
    await expect(next).toHaveCount(2);
    const scan = await new AxeBuilder({ page }).include('[aria-label="Reviews"]').withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await next.first().click();
    await expect(reviews(page).getByText("Discussion item 1", { exact: true })).toBeVisible();
    await next.last().click();
    await expect(reviews(page).getByText("Closed", { exact: true })).toHaveCount(1);
    await expect(reviews(page).getByRole("button", { name: "Request review", exact: true })).toHaveCount(0);
  });
});
