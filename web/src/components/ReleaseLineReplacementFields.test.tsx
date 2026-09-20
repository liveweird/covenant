import { useState } from "react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { jsonResponse } from "../test/http";
import type { ReleaseLineResponse } from "../api/releaseLines";
import ReleaseLineReplacementFields from "./ReleaseLineReplacementFields";

function Picker() {
  const [value, setValue] = useState<ReleaseLineResponse["replacement"]>(null);
  return <><ReleaseLineReplacementFields sourceContractId={5} sourceMajor={1} value={value} onChange={setValue} />
    <output aria-label="Selection">{JSON.stringify(value)}</output></>;
}

beforeEach(() => { localStorage.setItem("covenant.auth.token", "token"); });
afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

test("replacement choices page beyond the first page and retain the selected contract during search", async () => {
  const mockFetch = vi.fn((url: string) => {
    if (url.includes("/release-lines?")) return Promise.resolve(jsonResponse(200, { items: [{ major: 0 }, { major: 2 }], total: 2 }));
    const params = new URL(url, "https://local.test").searchParams;
    const items = params.get("q") ? [] : [{ id: params.get("page") === "2" ? 9 : 6, name: params.get("page") === "2" ? "Replacement" : "First", system: { name: "Commerce" } }];
    return Promise.resolve(jsonResponse(200, { items, page: Number(params.get("page")), pageSize: 20, total: 21 }));
  });
  vi.stubGlobal("fetch", mockFetch);
  const user = userEvent.setup();
  renderWithProviders(<Picker />);
  await user.click(await screen.findByRole("button", { name: "2" }));
  await user.click(screen.getByRole("combobox", { name: "Replacement contract" }));
  await user.click(await screen.findByRole("option", { name: "Replacement · Commerce" }));
  await user.click(screen.getByRole("combobox", { name: "Replacement release line" }));
  await user.click(await screen.findByRole("option", { name: "0.x" }));
  expect(screen.getByLabelText("Selection")).toHaveTextContent('"major":0');
  const contract = screen.getByRole("combobox", { name: "Replacement contract" });
  await user.clear(contract);
  await user.type(contract, "missing");
  await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => url.includes("q=missing"))).toBe(true));
  await user.keyboard("{Escape}");
  expect(screen.getByLabelText("Selection")).toHaveTextContent('"contractId":9');
  expect(screen.getByLabelText("Selection")).toHaveTextContent('"major":0');
});
