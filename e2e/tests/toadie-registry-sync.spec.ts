import AxeBuilder from "@axe-core/playwright";
import type { APIRequestContext, APIResponse, Page } from "@playwright/test";
import { apiAsAdmin, expect, login, PETSTORE, rowOperation, seedContractViaApi, test, uniqueText, type SeededContract } from "./helpers";
import { startToadieFixture } from "./toadie-fixture";

test.use({ actionTimeout: 15_000 });

async function createdId(response: Pick<APIResponse, "status" | "text" | "json">) {
  expect(response.status(), await response.text()).toBe(201);
  return (await response.json() as { id: number }).id;
}

async function refresh(api: APIRequestContext, id: number) {
  const initial = await (await api.get(`/api/v1/toadie-connections/${id}`)).json() as { lastAttemptAt: number };
  await expect.poll(() => Date.now() - initial.lastAttemptAt, { timeout: 35_000, intervals: [1000] }).toBeGreaterThan(31_000);
  const response = await api.post(`/api/v1/toadie-connections/${id}/refresh`);
  expect(response.status(), await response.text()).toBe(202);
  await expect.poll(async () => {
    const state = await (await api.get(`/api/v1/toadie-connections/${id}`)).json() as {
      refreshing: boolean; lastErrorCode: string | null; lastSuccessAt: number | null;
    };
    return !state.refreshing && state.lastErrorCode == null && (state.lastSuccessAt ?? 0) > initial.lastAttemptAt;
  }, { timeout: 15_000 }).toBe(true);
}

test.describe.serial("Toadie registry synchronization", () => {
  let api: APIRequestContext;
  let upstream: Awaited<ReturnType<typeof startToadieFixture>>;
  let seeded: SeededContract;
  let connectionId: number;
  let memberId: number;
  let environmentId: number;
  let importedSystemId: number;
  const connectionName = uniqueText("e2e-registry");
  const domainName = uniqueText("remote-domain");
  const systemName = uniqueText("remote-system");
  const teamName = uniqueText("remote-team");
  const orphanName = uniqueText("remote-orphan");
  const memberEmail = `${uniqueText("registry-member")}@example.test`;
  const memberPassword = "Registry-test-1!";

  test.beforeAll(async () => {
    ({ api } = await apiAsAdmin());
    upstream = await startToadieFixture();
    upstream.renameEntity("7", domainName);
    upstream.renameEntity("5", systemName);
    upstream.renameEntity("6", teamName);
    upstream.renameEntity("9", orphanName);
    seeded = await seedContractViaApi(api, "e2e-registry", PETSTORE("Registry ownership", "1.0.0"));
    memberId = await createdId(await api.post("/api/v1/users", {
      data: { name: "Registry member", email: memberEmail, password: memberPassword, roles: [] },
    }));
    expect((await api.post(`/api/v1/teams/${seeded.teamId}/members/${memberId}`)).status()).toBe(204);
    environmentId = await createdId(await api.post("/api/v1/environments", {
      data: { name: uniqueText("registry-env"), systemId: seeded.systemId, httpBaseUrl: "http://localhost:12345" },
    }));
  });

  test.afterAll(async () => {
    const failures: string[] = [];
    try {
      if (api) {
        const paths = [
          importedSystemId && `/api/v1/systems/${importedSystemId}`,
          environmentId && `/api/v1/environments/${environmentId}`,
          seeded && `/api/v1/contracts/${seeded.contractId}`,
          seeded && `/api/v1/systems/${seeded.systemId}`,
          seeded && `/api/v1/domains/${seeded.domainId}`,
          seeded && `/api/v1/teams/${seeded.teamId}`,
          connectionId && `/api/v1/toadie-connections/${connectionId}`,
          memberId && `/api/v1/users/${memberId}`,
        ];
        for (const path of paths) {
          if (!path) continue;
          try {
            const response = await api.delete(path);
            if (![204, 404].includes(response.status())) failures.push(`${path}: ${response.status()}`);
          } catch (error) { failures.push(`${path}: ${String(error)}`); }
        }
      }
    } finally {
      await api?.dispose();
      await upstream?.close();
    }
    expect(failures).toEqual([]);
  });

  async function syncRecord(page: Page, kind: string, sourceTitle: string, localName?: string, fallbackName?: string) {
    await page.goto("/toadie-connections");
    await rowOperation(page, connectionName, "Sync registry metadata");
    const dialog = page.getByRole("dialog", { name: `Sync registry metadata from ${connectionName}` });
    await dialog.getByRole("combobox", { name: "Registry kind", exact: true }).click();
    await page.getByRole("option", { name: kind, exact: true }).click();
    const row = dialog.getByRole("table", { name: "Available Toadie registry records" })
      .getByRole("row").filter({ hasText: sourceTitle });
    await row.getByRole("checkbox").check();
    if (localName) {
      await row.getByRole("combobox").first().click();
      await page.getByRole("option", { name: localName, exact: true }).click();
    }
    if (fallbackName) {
      await row.getByRole("combobox").last().click();
      await page.getByRole("option", { name: fallbackName, exact: true }).click();
    }
    await dialog.getByRole("button", { name: "Preview changes", exact: true }).click();
    await expect(dialog.getByRole("region", { name: "Synchronization preview" })).toContainText(sourceTitle);
    await dialog.getByRole("button", { name: "Apply preview", exact: true }).scrollIntoViewIfNeeded();
    const previewImage = test.info().outputPath(`registry-preview-${kind}.png`);
    await page.screenshot({ path: previewImage, fullPage: true });
    await test.info().attach(`Registry preview: ${kind}`, { path: previewImage, contentType: "image/png" });
    const [applied] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith(`/toadie-connections/${connectionId}/registry-sync`) && response.request().method() === "POST"),
      dialog.getByRole("button", { name: "Apply preview", exact: true }).click(),
    ]);
    expect(applied.status(), await applied.text()).toBe(200);
    const localId = (await applied.json() as { items: { localId: number }[] }).items[0].localId;
    if (kind === "Systems" && !localName) importedSystemId = localId;
    await expect(dialog).toHaveCount(0);
    return localId;
  }

  async function assertPreserved() {
    const contract = await (await api.get(`/api/v1/contracts/${seeded.contractId}`)).json() as { system: { id: number }; owner: { id: number; kind: string } };
    expect(contract.system.id).toBe(seeded.systemId);
    expect(contract.owner.id).toBe(seeded.teamId);
    expect(contract.owner.kind).toBe("TEAM");
    const team = await (await api.get(`/api/v1/teams/${seeded.teamId}`)).json() as { members: { userId: number }[] };
    expect(team.members.map(({ userId }) => userId)).toEqual([memberId]);
    const environment = await (await api.get(`/api/v1/environments/${environmentId}`)).json() as { systemId: number; httpBaseUrl: string };
    expect(environment.systemId).toBe(seeded.systemId);
    expect(environment.httpBaseUrl).toBe("http://localhost:12345");
  }

  test("an administrator previews and links Port registries while retaining local ownership", async ({ page }, testInfo) => {
    test.setTimeout(120_000);
    await login(page);
    await page.goto("/toadie-connections");
    await page.getByRole("button", { name: "New connection", exact: true }).click();
    const editor = page.getByRole("dialog", { name: "New Toadie connection", exact: true });
    await editor.getByRole("textbox", { name: "Name", exact: true }).fill(connectionName);
    await editor.getByRole("textbox", { name: "Backend base URL" }).fill(upstream.baseUrl);
    await editor.getByRole("textbox", { name: "Browser URL" }).fill(upstream.browserUrl);
    await editor.getByRole("textbox", { name: "API key", exact: true }).fill(upstream.key);
    await editor.getByRole("button", { name: "Registry metadata mapping", exact: true }).click();
    await editor.getByRole("switch", { name: /Enable registry metadata sync/ }).check();
    for (const name of ["Domain description property", "System description property", "Team description property"]) {
      await editor.getByRole("textbox", { name, exact: true }).fill("description");
    }
    await editor.getByRole("checkbox", { name: "I understand that Toadie domain nesting will be flattened" }).check();
    const [created] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith("/api/v1/toadie-connections") && response.request().method() === "POST"),
      editor.getByRole("button", { name: "Create", exact: true }).click(),
    ]);
    connectionId = await createdId(created);
    await expect(editor).toHaveCount(0);
    await expect.poll(async () => {
      const state = await (await api.get(`/api/v1/toadie-connections/${connectionId}`)).json() as { lastSuccessAt: number | null };
      return state.lastSuccessAt != null;
    }).toBe(true);
    const localDomain = await (await api.get(`/api/v1/domains/${seeded.domainId}`)).json() as { name: string };
    expect(await syncRecord(page, "Domains", domainName, localDomain.name)).toBe(seeded.domainId);
    const localSystem = await (await api.get(`/api/v1/systems/${seeded.systemId}`)).json() as { name: string };
    expect(await syncRecord(page, "Systems", systemName, `${domainName} / ${localSystem.name}`)).toBe(seeded.systemId);
    importedSystemId = await syncRecord(page, "Systems", orphanName, undefined, domainName);
    expect(await syncRecord(page, "Teams", teamName, seeded.teamName)).toBe(seeded.teamId);
    for (const [path, description] of [
      [`domains/${seeded.domainId}`, "Description of Commerce domain"],
      [`systems/${seeded.systemId}`, "Description of Commerce system"],
      [`teams/${seeded.teamId}`, "Description of Retail team"],
    ]) {
      expect((await (await api.get(`/api/v1/${path}`)).json() as { description: string }).description).toBe(description);
    }
    await assertPreserved();
    await login(page, memberEmail, memberPassword);
    await page.goto("/teams");
    const teamRow = page.getByRole("row").filter({ hasText: teamName });
    await expect(teamRow).toContainText("Synchronized");
    await expect(teamRow.getByRole("button", { name: /Operations/ })).toHaveCount(0);
    const scan = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
    expect(scan.violations.map(({ id, impact }) => ({ id, impact }))).toEqual([]);
    await testInfo.attach("Registry source visible to a team member", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
    await page.goto(`/contracts/${seeded.contractId}`);
    await expect(page.getByRole("link", { name: "New version", exact: true })).toBeVisible();
  });

  test("refresh updates linked metadata and preserves records when a source disappears", async ({ page }) => {
    test.setTimeout(150_000);
    upstream.renameEntity("7", `${domainName}-renamed`);
    upstream.renameEntity("5", `${systemName}-renamed`);
    upstream.renameEntity("6", `${teamName}-renamed`);
    await refresh(api, connectionId);
    await login(page);
    await page.goto("/domains");
    await expect(page.getByRole("row").filter({ hasText: `${domainName}-renamed` })).toContainText("Synchronized");
    await page.goto("/systems");
    await expect(page.getByRole("row").filter({ hasText: `${systemName}-renamed` })).toContainText(`${domainName}-renamed`);
    await page.goto("/teams");
    await expect(page.getByRole("row").filter({ hasText: `${teamName}-renamed` })).toContainText("Synchronized");
    await assertPreserved();
    upstream.removeEntity("6");
    await refresh(api, connectionId);
    await page.reload();
    await expect(page.getByRole("row").filter({ hasText: `${teamName}-renamed` })).toContainText("Missing in Toadie");
    await assertPreserved();
    await page.getByRole("row").filter({ hasText: `${teamName}-renamed` })
      .getByRole("button", { name: "Detach Toadie source", exact: true }).click();
    const detach = page.getByRole("dialog", { name: "Detach Toadie source?", exact: true });
    const [detached] = await Promise.all([
      page.waitForResponse((response) => response.url().endsWith(`/teams/${seeded.teamId}/toadie-source`) && response.request().method() === "DELETE"),
      detach.getByRole("button", { name: "Detach Toadie source", exact: true }).click(),
    ]);
    expect(detached.status()).toBe(204);
    await expect(detach).toHaveCount(0);
    await rowOperation(page, `${teamName}-renamed`, "Edit");
    const edit = page.getByRole("dialog");
    await edit.getByRole("textbox", { name: "Name", exact: true }).fill(`${teamName}-local`);
    await edit.getByRole("button", { name: "Save", exact: true }).click();
    await expect(edit).toHaveCount(0);
    await expect(page.getByRole("row").filter({ hasText: `${teamName}-local` })).not.toContainText("Missing in Toadie");
    await assertPreserved();
  });
});
