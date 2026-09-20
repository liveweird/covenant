import AxeBuilder from "@axe-core/playwright";
import { request, type APIRequestContext, type APIResponse, type Page } from "@playwright/test";
import { BASE_URL } from "../playwright.config";
import { apiAsAdmin, expect, login, openFilters, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText, type SeededContract } from "./helpers";

// Owns one hierarchy, one regular follower and their review data. ADMIN's permission to
// edit the fixture is deliberately not personal ownership: its owning team starts empty.
test.describe.serial("review inbox", () => {
  let api: APIRequestContext;
  let reviewerApi: APIRequestContext;
  let seeded: SeededContract;
  let reviewerId = 0;
  let reviewId = 0;
  const reviewerName = uniqueText("e2e-inbox-reviewer");
  const reviewerEmail = `${reviewerName}@covenant.local`;
  const reviewerPassword = `Inbox-${uniqueText("test")}!42`;
  const versionApi = () => `/api/v1/contracts/${seeded.contractId}/versions/${seeded.versionId}`;
  const reviews = (page: Page) => page.getByRole("region", { name: "Reviews", exact: true });
  const inbox = (page: Page) => page.getByRole("table", { name: "Review inbox", exact: true });
  const row = (page: Page) => inbox(page).getByRole("row").filter({ hasText: seeded.contractName });

  async function ok(response: APIResponse, status: number) {
    expect(response.status(), await response.text()).toBe(status);
  }

  async function openInbox(page: Page, scope = "RELATED", attention?: string) {
    const query = new URLSearchParams({ q: seeded.contractName, scope });
    if (attention) query.set("attention", attention);
    await page.goto(`/reviews?${query}`);
    await expect(page.getByRole("heading", { name: "Review inbox", exact: true })).toBeVisible();
  }

  async function requestReview(revision: number) {
    const response = await api.post(`${versionApi()}/reviews`, { data: { expectedContentRevision: revision } });
    await ok(response, 201);
    reviewId = ((await response.json()) as { id: number }).id;
  }

  async function transition(to: string) {
    await ok(await api.post(`${versionApi()}/transition`, { data: { to } }), 200);
  }

  test.beforeAll(async () => {
    ({ api } = await apiAsAdmin());
    seeded = await seedContractViaApi(api, "e2e-inbox", PETSTORE("Inbox API", "1.0.0"));
    const created = await api.post("/api/v1/users", { data: {
      name: reviewerName, email: reviewerEmail, password: reviewerPassword, roles: [],
    } });
    await ok(created, 201);
    reviewerId = ((await created.json()) as { id: number }).id;
    const signedIn = await api.post("/api/v1/login", { data: { email: reviewerEmail, password: reviewerPassword } });
    await ok(signedIn, 200);
    const { token } = await signedIn.json() as { token: string };
    reviewerApi = await request.newContext({ baseURL: BASE_URL, extraHTTPHeaders: { Authorization: `Bearer ${token}` } });
    await ok(await reviewerApi.put(`/api/v1/contracts/${seeded.contractId}/subscription`), 204);
    await transition("PROPOSED");
    await requestReview(1);
  });

  test.afterAll(async () => {
    if (!api) return;
    try {
      if (seeded) {
        const response = await api.get(versionApi());
        if (response.ok()) {
          const { lifecycle } = await response.json() as { lifecycle: string };
          if (lifecycle === "ACTIVE") await transition("DEPRECATED");
          if (lifecycle === "ACTIVE" || lifecycle === "DEPRECATED") await transition("RETIRED");
        }
        await teardownSeededContract(api, seeded);
      }
      if (reviewerId) await ok(await api.delete(`/api/v1/users/${reviewerId}`), 204);
    } finally {
      await reviewerApi?.dispose();
      await api.dispose();
    }
  });

  test("personal review scope respects follows and opens current document feedback", async ({ page }, testInfo) => {
    await login(page);
    await openInbox(page);
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
    await openFilters(page);
    await page.getByRole("combobox", { name: "Review scope", exact: true }).click();
    await page.getByRole("option", { name: "All contracts", exact: true }).click();
    await expect(row(page)).toHaveCount(1);

    await login(page, reviewerEmail, reviewerPassword);
    await openInbox(page, "RELATED", "AWAITING_MY_REVIEW");
    await expect(row(page)).toHaveCount(1);
    const scan = await new AxeBuilder({ page }).include("main").withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Review inbox desktop", { body: await page.screenshot({ fullPage: true, animations: "disabled" }), contentType: "image/png" });
    await page.setViewportSize({ width: 390, height: 844 });
    await expect(page.getByRole("link", { name: "Review inbox", exact: true })).not.toBeInViewport();
    expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390);
    await testInfo.attach("Review inbox mobile", { body: await page.screenshot({ fullPage: true, animations: "disabled" }), contentType: "image/png" });
    await page.setViewportSize({ width: 1280, height: 720 });
    await page.getByRole("link", { name: `Review ${seeded.contractName} 1.0.0`, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded.contractId}/versions/${seeded.versionId}#reviews$`));
    await expect(reviews(page)).toBeInViewport();
    await reviews(page).getByRole("textbox", { name: "Review comment", exact: true }).fill("I am checking the response examples.");
    await reviews(page).getByRole("button", { name: "Add comment", exact: true }).click();
    await expect(reviews(page).getByText("I am checking the response examples.", { exact: true })).toBeVisible();
    await openInbox(page, "FOLLOWED", "AWAITING_MY_REVIEW");
    await expect(row(page)).toHaveCount(1);
    await page.getByRole("link", { name: `Review ${seeded.contractName} 1.0.0`, exact: true }).click();
    await reviews(page).getByRole("button", { name: "Approve", exact: true }).click();
    await expect(reviews(page).getByText("Approved", { exact: true }).first()).toBeVisible();
    await openInbox(page, "FOLLOWED", "AWAITING_MY_REVIEW");
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
    await openInbox(page, "FOLLOWED");
    await expect(row(page)).toHaveCount(1);
    await ok(await reviewerApi.delete(`/api/v1/contracts/${seeded.contractId}/subscription`), 204);
    await page.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
  });

  test("feedback and closed rounds guide follow-up without retaining superseded requests", async ({ page }) => {
    await ok(await reviewerApi.post(`/api/v1/version-reviews/${reviewId}/entries`, { data: {
      expectedContentRevision: 1, kind: "CHANGES_REQUESTED", body: "Please explain the empty response case.",
    } }), 201);
    await ok(await api.put(`/api/v1/contracts/${seeded.contractId}/subscription`), 204);
    await login(page);
    await openInbox(page, "RELATED", "CHANGES_REQUESTED");
    await expect(row(page)).toHaveCount(1);
    const updated = await api.put(`${versionApi()}/content`, { data: { content: PETSTORE("Inbox API clarified", "1.0.0") } });
    await ok(updated, 200);
    const { contentRevision } = await updated.json() as { contentRevision: number };
    await openInbox(page, "RELATED", "CHANGES_REQUESTED");
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
    await expect(page.getByRole("button", { name: "Needs new review", exact: true })).toContainText("1");
    await page.getByRole("button", { name: "Needs new review", exact: true }).click();
    await expect(row(page)).toHaveCount(1);
    await requestReview(contentRevision);
    await page.getByRole("button", { name: "Refresh", exact: true }).click();
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
    await page.getByRole("button", { name: "All reviews", exact: true }).click();
    await expect(row(page)).toHaveCount(1);
    await transition("DRAFT");
    await transition("PROPOSED");
    await openInbox(page, "RELATED", "NEEDS_NEW_REVIEW");
    await expect(row(page)).toHaveCount(1);
    await requestReview(contentRevision);
    await transition("ACTIVE");
    await openInbox(page, "ALL");
    await expect(page.getByText("No review items match these filters.", { exact: true })).toBeVisible();
    await expect(row(page)).toHaveCount(0);
  });
});
