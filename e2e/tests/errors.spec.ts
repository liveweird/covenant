// The Errors report at /errors: a soft-error version saved through the waiver (allowInvalid=true,
// the API-side Save-anyway journey) shows up as a row with an OAS_PARSE finding badge; the severity
// chips are server filters that narrow it away and back, and the version link opens the version
// page. Owns: its throwaway domain/system/team/contract (`e2e-errors-*` names), API-seeded and
// torn down.
import { apiAsAdmin, BROKEN_REF, expect, login, openFilters, PETSTORE, seedContractViaApi, teardownSeededContract, test, uniqueText } from "./helpers";

test("a waived soft error lands on the Errors report, the severity chips narrow it, and the version link opens it", async ({ page }) => {
  const { api } = await apiAsAdmin();
  const seeded = await seedContractViaApi(api, "e2e-errors", PETSTORE(uniqueText("e2e-errors-doc"), "1.0.0"));
  const brokenVersion = await api.post(`/api/v1/contracts/${seeded.contractId}/versions?allowInvalid=true`, {
    data: { version: "1.1.0", content: BROKEN_REF(seeded.contractName, "1.1.0") },
  });
  expect(brokenVersion.status(), await brokenVersion.text()).toBe(201);
  const brokenVersionId: number = (await brokenVersion.json()).id;

  try {
    await login(page);
    await page.goto("/errors");
    await expect(page.getByRole("heading", { name: "Errors" })).toBeVisible();
    await openFilters(page);
    await page.getByLabel("Search", { exact: true }).fill(seeded.contractName);

    const versionLink = page.getByRole("link", { name: `Open version 1.1.0 of ${seeded.contractName}` });
    await expect(versionLink).toBeVisible();
    // Scoped by the link, not by "1.1.0": until the debounced search narrows the table, every
    // other contract's 1.1.0 row is on the page too.
    const row = page.getByRole("row").filter({ has: versionLink });
    await expect(row.getByText(/OAS_PARSE/)).toBeVisible();

    // Toggle the "Error" chip off: the OAS_PARSE badge (an ERROR finding) disappears from the row —
    // asserted on the badge itself, not on an empty table, since the live checker may leave a 1.0.0
    // WARN row (or a WARN of its own on 1.1.0).
    // The Chip's checkbox input is visually hidden — click its label (Toadie's errors.spec idiom).
    const severityGroup = page.getByRole("group", { name: "Severity" });
    await severityGroup.getByText("Error", { exact: true }).click();
    await expect(row.getByText(/OAS_PARSE/)).toHaveCount(0);

    // Toggle it back on: the badge and the version link return.
    await severityGroup.getByText("Error", { exact: true }).click();
    await expect(versionLink).toBeVisible();
    await expect(row.getByText(/OAS_PARSE/)).toBeVisible();

    await versionLink.click();
    await expect(page).toHaveURL(new RegExp(`/contracts/${seeded.contractId}/versions/${brokenVersionId}$`));

  } finally {
    await teardownSeededContract(api, seeded);
    await api.dispose();
  }
});
