import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { Route, Routes } from "react-router-dom";
import ContractDetails from "./ContractDetails";
import { calledUrl, CONTRACT, EVENTS_PAGE, findCall, serve, signIn, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id" element={<ContractDetails />} />
      <Route path="/contracts" element={<h2>Contracts list</h2>} />
    </Routes>,
    { route: "/contracts/5" },
  );
}

describe("ContractDetails page", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:x");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    localStorage.clear();
  });

  test("shows the placement, the owner, the versions (highest first) and the writer's actions", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
    });
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "orders-api" })).toBeInTheDocument();
    expect(screen.getByText("Payments / gateway")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Payments Team" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: "Open version 1.1.0" })).toHaveAttribute("href", "/contracts/5/versions/11");
    expect(screen.getByRole("link", { name: "Open version 1.0.0" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New version" })).toHaveAttribute("href", "/contracts/5/versions/new?from=11");
    expect(screen.getByRole("link", { name: "Compare versions" })).toHaveAttribute("href", "/contracts/5/diff");
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/5/versions?") && u.includes("sort=-version"))).toBeDefined();
    expect(screen.getByText("Active")).toBeInTheDocument();
    const history = await screen.findByRole("region", { name: "History" });
    expect(within(history).getByText("Version 1.0.0: Proposed → Active")).toBeInTheDocument();
    expect(within(history).getByText("Contract created (OpenAPI)")).toBeInTheDocument();
  });

  test("Follow puts the subscription and the button reflects the server's flag", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
      "PUT /api/v1/contracts/5/subscription": { status: 204 },
    });
    const user = userEvent.setup();
    renderPage();
    const follow = await screen.findByRole("button", { name: "Follow · 0" });
    expect(follow).toHaveAttribute("aria-pressed", "false");
    await user.click(follow);
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/subscription")).toBeDefined());
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, subscribed: true, subscriberCount: 1 } }, "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE }, "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE } });
    const { unmount } = renderPage();
    expect(await screen.findAllByRole("button", { name: "Following · 1" })).not.toHaveLength(0);
    unmount();
  });

  test("a reader has no write actions; the More menu still exports", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, canWrite: false } },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
      "GET /api/v1/contracts/5/export": { status: 200, body: { contract: CONTRACT, versions: [] } },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "orders-api" });
    expect(screen.queryByRole("link", { name: "New version" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "More actions" }));
    expect(screen.queryByRole("menuitem", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete" })).not.toBeInTheDocument();
    await user.click(await screen.findByRole("menuitem", { name: "Export (JSON)" }));
    await waitFor(() => expect(findCall(mockFetch, "GET", "/api/v1/contracts/5/export")).toBeDefined());
    await user.keyboard("{Escape}");
    await user.click(await screen.findByRole("button", { name: "Operations for 1.0.0" }));
    expect(await screen.findByRole("menuitem", { name: "Compare with latest" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete version 1.0.0" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "New version from this" })).not.toBeInTheDocument();
  });

  test("row operations: download fetches the raw text; only a DRAFT offers Delete", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
      "DELETE /api/v1/contracts/5/versions/11": { status: 204 },
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for 1.0.0" }));
    expect(await screen.findByRole("menuitem", { name: "Compare with latest" })).toHaveAttribute("href", "/contracts/5/diff?from=10&to=11");
    expect(screen.queryByRole("menuitem", { name: "Delete version 1.0.0" })).not.toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(await screen.findByRole("button", { name: "Operations for 1.1.0" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete version 1.1.0" }));
    expect(await screen.findByText(/Version 1.1.0 of "orders-api" will be deleted/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/contracts/5/versions/11")).toBeDefined());
  });

  test("download fetches the raw content once", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
      "GET /api/v1/contracts/5/versions/10/content": () => ({ status: 200, body: { raw: true } }),
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Operations for 1.0.0" }));
    await user.click(await screen.findByRole("menuitem", { name: "Download" }));
    await waitFor(() => expect(findCall(mockFetch, "GET", "/api/v1/contracts/5/versions/10/content")).toBeDefined());
  });

  test("deleting the contract goes back to the list; a 409 explains itself", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "GET /api/v1/contracts/5/events?": { status: 200, body: EVENTS_PAGE },
      "DELETE /api/v1/contracts/5": { status: 409, body: { title: "Conflict", status: 409 } },
    });
    const user = userEvent.setup();
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "orders-api" });
    await user.click(screen.getByRole("button", { name: "More actions" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText(/still has active or deprecated versions/)).toBeInTheDocument();
  });

  test("a missing contract shows the not-found message; an empty version list the empty state", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 404, body: { title: "Not Found", status: 404 } } });
    const { unmount } = renderPage();
    expect(await screen.findByText("This contract does not exist (or was deleted).")).toBeInTheDocument();
    unmount();
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, latestVersion: null, versionCount: 0 } },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: { ...VERSION_PAGE, items: [], total: 0 } },
    });
    renderPage();
    expect(await screen.findByText("No versions yet — add the first document")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New version" })).toHaveAttribute("href", "/contracts/5/versions/new");
  });
});
