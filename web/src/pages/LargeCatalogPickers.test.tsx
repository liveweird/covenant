import { afterEach, beforeEach, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { CONTRACT, serve, signIn, SYSTEMS_PAGE, type FetchMock } from "../test/contractsFixtures";
import CreateContract from "./CreateContract";
import EditContract from "./EditContract";
import ImportContract from "./ImportContract";
import Environments from "./Environments";

let mockFetch: FetchMock;
beforeEach(() => {
  signIn();
  mockFetch = vi.fn();
  vi.stubGlobal("fetch", mockFetch);
  serve(mockFetch, {
    "GET /api/v1/systems?": (url) => {
      const page = Number(new URL(url, "http://localhost").searchParams.get("page"));
      const items = page === 1
        ? Array.from({ length: 100 }, (_, index) => ({ ...SYSTEMS_PAGE.items[0], id: index + 1, name: `System ${index + 1}` }))
        : [{ ...SYSTEMS_PAGE.items[0], id: 101, name: "Late system" }];
      return { status: 200, body: { items, page, pageSize: 100, total: 101 } };
    },
    "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, system: { id: 101, name: "Late system" } } },
    "GET /api/v1/environments?": { status: 200, body: { items: [], page: 1, pageSize: 20, total: 0 } },
  });
});
afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.clear();
});

test.each([
  { name: "create", route: "/contracts/new", path: "/contracts/new", element: <CreateContract /> },
  { name: "import", route: "/contracts/import", path: "/contracts/import", element: <ImportContract /> },
])("$name lets the user choose system 101", async ({ route, path, element }) => {
  const user = userEvent.setup();
  renderWithProviders(<Routes><Route path={path} element={element} /></Routes>, { route });
  const picker = await screen.findByLabelText("System", { selector: "input" });
  await user.click(picker);
  await user.click(await screen.findByRole("option", { name: "Late system" }));
  expect(picker).toHaveValue("Late system");
});

test("edit displays the stored system beyond page one", async () => {
  renderWithProviders(<Routes><Route path="/contracts/:id/edit" element={<EditContract />} /></Routes>, { route: "/contracts/5/edit" });
  const picker = await screen.findByLabelText("System", { selector: "input" });
  await waitFor(() => expect(picker).toHaveValue("Late system"));
  expect(picker).toBeDisabled();
});

test("the environment editor lets the user choose system 101", async () => {
  const user = userEvent.setup();
  renderWithProviders(<Environments />);
  const create = await screen.findByRole("button", { name: "New environment" });
  await waitFor(() => expect(create).toBeEnabled());
  await user.click(create);
  const dialog = await screen.findByRole("dialog");
  const picker = within(dialog).getByLabelText("System", { selector: "input" });
  await user.click(picker);
  await user.click(await screen.findByRole("option", { name: "Payments / Late system" }));
  expect(picker).toHaveValue("Payments / Late system");
});
