import type { Route } from "@playwright/test";
import { createUserViaUi, deleteUserRow, expect, login, test } from "./helpers";

test("a new account never sees the previous account's cached notifications", async ({ page }) => {
  await login(page);
  const nextUser = await createUserViaUi(page, "E2E Session Cache");

  let holdNextAccount = false;
  let releaseNextRequest!: () => void;
  let markNextRequest!: () => void;
  let markNextRequestSettled!: () => void;
  const nextRequestGate = new Promise<void>((resolve) => { releaseNextRequest = resolve; });
  const nextRequestSeen = new Promise<void>((resolve) => { markNextRequest = resolve; });
  const nextRequestSettled = new Promise<void>((resolve) => { markNextRequestSettled = resolve; });
  let nextRequestPending = false;
  const unreadUrl = /\/api\/v1\/notifications\?.*pageSize=1(?:&|$)/;
  const notificationHandler = async (route: Route) => {
    if (holdNextAccount) {
      nextRequestPending = true;
      markNextRequest();
      await nextRequestGate;
    }
    try {
      await route.fulfill({ json: { items: [], page: 1, pageSize: 1, total: holdNextAccount ? 0 : 1 } });
    } finally {
      if (nextRequestPending) markNextRequestSettled();
    }
  };
  await page.route(unreadUrl, notificationHandler);

  try {
    // Reload only while account A owns the page, so its unread result populates the same
    // QueryClient that will survive the automatic sign-out and account B's sign-in.
    await page.reload();
    await expect(page.getByRole("button", { name: "Notifications (1 unread)" })).toBeVisible();

    // Simulate an expired access token and a definitively rejected refresh token. Opening the
    // bell fires an uncached list query while keeping the SPA's QueryClient mounted.
    await page.evaluate(() => {
      localStorage.setItem("covenant.auth.token", "invalid-access-token");
      localStorage.setItem("covenant.auth.refreshToken", "invalid-refresh-token");
    });
    await page.getByRole("button", { name: "Notifications (1 unread)" }).click();
    await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();

    holdNextAccount = true;
    await page.getByRole("textbox", { name: "Email" }).fill(nextUser.email);
    await page.getByRole("textbox", { name: "Password" }).fill(nextUser.password);
    await page.getByRole("button", { name: "Sign in" }).click();
    await nextRequestSeen;
    // B's unread request is still pending. Cached A data must not appear in the new shell.
    await expect(page.getByRole("button", { name: "Notifications (0 unread)" })).toBeVisible();
  } finally {
    releaseNextRequest();
    if (nextRequestPending) await nextRequestSettled;
    await page.unroute(unreadUrl, notificationHandler);
    await login(page);
    await deleteUserRow(page, nextUser.name);
  }
});

test("a late draft result cannot appear after another tab switches accounts", async ({ page }) => {
  await login(page);
  const nextUser = await createUserViaUi(page, "E2E Session Cache Mutation");
  const otherTab = await page.context().newPage();
  let releaseDraft!: () => void;
  let markDraftStarted!: () => void;
  let markDraftSettled!: () => void;
  const draftGate = new Promise<void>((resolve) => { releaseDraft = resolve; });
  const draftStarted = new Promise<void>((resolve) => { markDraftStarted = resolve; });
  const draftSettled = new Promise<void>((resolve) => { markDraftSettled = resolve; });
  let draftPending = false;
  const draftUrl = /\/api\/v1\/contracts\/infer$/;
  const draftHandler = async (route: Route) => {
    draftPending = true;
    markDraftStarted();
    await draftGate;
    try {
      await route.fulfill({ json: {
        content: "openapi: 3.1.0\ninfo:\n  title: A-private-draft\n  version: 1.0.0\npaths: {}\n",
        format: "yaml",
        notes: [],
      } });
    } finally {
      markDraftSettled();
    }
  };
  await page.route(draftUrl, draftHandler);

  try {
    await page.goto("/contracts/infer");
    await page.getByRole("textbox", { name: "Name", exact: true }).fill("A private draft");
    await page.getByLabel("URL", { exact: true }).fill("https://api.example.test/orders/42");
    await page.getByRole("button", { name: "Add sample" }).click();
    await page.getByRole("button", { name: "Generate draft" }).click();
    await draftStarted;

    await otherTab.goto("/");
    const response = await page.context().request.post(new URL("/api/v1/login", page.url()).toString(), {
      data: { email: nextUser.email, password: nextUser.password },
    });
    expect(response.ok(), await response.text()).toBe(true);
    const session = await response.json() as {
      token: string; refreshToken: string; roles: string[]; userId: number; disabledFeatures: string[];
    };
    // A real second tab replaces localStorage. The first tab receives browser storage events
    // while its mutation is still pending and its route stays mounted.
    await otherTab.evaluate((data) => {
      localStorage.setItem("covenant.auth.token", data.token);
      localStorage.setItem("covenant.auth.refreshToken", data.refreshToken);
      localStorage.setItem("covenant.auth.roles", JSON.stringify(data.roles));
      localStorage.setItem("covenant.auth.userId", String(data.userId));
      localStorage.setItem("covenant.auth.disabledFeatures", JSON.stringify(data.disabledFeatures));
      localStorage.setItem("covenant.auth.sessionIdentity", crypto.randomUUID());
    }, session);
    await expect(page.getByRole("textbox", { name: "Name", exact: true })).toHaveValue("");

    const draftResponse = page.waitForResponse((r) => r.request().method() === "POST" && draftUrl.test(r.url()));
    releaseDraft();
    await draftResponse;
    // Let the mutation's response handler run before asserting that B still has no draft.
    await page.evaluate(() => new Promise<void>((resolve) => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))));
    await expect(page.getByRole("button", { name: "Open in editor" })).toBeDisabled();
    await expect(page.locator('.cm-content[aria-label="Inferred document"]')).not.toContainText("A-private-draft");
  } finally {
    releaseDraft();
    if (draftPending) await draftSettled;
    await page.unroute(draftUrl, draftHandler);
    await otherTab.close();
    await page.evaluate(() => {
      for (const key of Object.keys(localStorage)) {
        if (key.startsWith("covenant.auth.")) localStorage.removeItem(key);
      }
    });
    await login(page);
    await deleteUserRow(page, nextUser.name);
  }
});
