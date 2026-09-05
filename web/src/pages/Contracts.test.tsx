import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import Contracts from "./Contracts";
import { calledUrl, CONTRACT, CONTRACT_PAGE, findCall, serve, signIn, type FetchMock } from "../test/contractsFixtures";

describe("Contracts page", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders a row with the name link, type, system/domain, owner and the latest version", async () => {
    serve(mockFetch, { "GET /api/v1/contracts?": { status: 200, body: CONTRACT_PAGE } });
    renderWithProviders(<Contracts />);
    expect(await screen.findByRole("link", { name: "Open contract orders-api" })).toHaveAttribute("href", "/contracts/5");
    expect(screen.getByText("OpenAPI")).toBeInTheDocument();
    expect(screen.getByText("gateway")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Payments Team" })).toHaveAttribute("href", "/teams/3");
    expect(screen.getByText("1.1.0")).toBeInTheDocument();
    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.getByText("1 warnings")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New contract" })).toHaveAttribute("href", "/contracts/new");
    expect(screen.getByRole("link", { name: "Import" })).toHaveAttribute("href", "/contracts/import");
  });

  test("a reader gets no row operations; a writer deletes after confirming", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts?": { status: 200, body: { ...CONTRACT_PAGE, items: [{ ...CONTRACT, canWrite: false }, { ...CONTRACT, id: 6, name: "refunds-api", latestVersion: null, versionCount: 0 }] } },
      "DELETE /api/v1/contracts/6": { status: 204 },
    });
    const user = userEvent.setup();
    renderWithProviders(<Contracts />);
    await screen.findByText("orders-api");
    expect(screen.queryByRole("button", { name: "Operations for orders-api" })).not.toBeInTheDocument();
    expect(screen.getByText("no versions")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Operations for refunds-api" }));
    expect(await screen.findByRole("menuitem", { name: "Edit refunds-api" })).toHaveAttribute("href", "/contracts/6/edit");
    await user.click(screen.getByRole("menuitem", { name: "Delete refunds-api" }));
    expect(await screen.findByText(/"refunds-api" and its 0 version/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/contracts/6")).toBeDefined());
  });

  test("a delete refused for published versions renders the 409 vocabulary", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts?": { status: 200, body: CONTRACT_PAGE },
      "DELETE /api/v1/contracts/5": { status: 409, body: { title: "Conflict", status: 409 } },
    });
    const user = userEvent.setup();
    renderWithProviders(<Contracts />);
    await user.click(await screen.findByRole("button", { name: "Operations for orders-api" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete orders-api" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText(/still has active or deprecated versions/)).toBeInTheDocument();
  });

  test("the filters ride the query: text, only-with-errors, type and lifecycle repeat", async () => {
    serve(mockFetch, { "GET /api/v1/contracts?": { status: 200, body: CONTRACT_PAGE } });
    const user = userEvent.setup();
    renderWithProviders(<Contracts />);
    await screen.findByText("orders-api");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Search"), "ord");
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts?") && u.includes("q=ord"))).toBeDefined());
    await user.click(screen.getByRole("switch", { name: "Only with errors" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts?") && u.includes("hasErrors=true"))).toBeDefined());
    await user.click(screen.getByLabelText("Type", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "ODCS" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts?") && u.includes("type=ODCS"))).toBeDefined());
    await user.click(screen.getByLabelText("Lifecycle", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Active" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts?") && u.includes("lifecycle=ACTIVE"))).toBeDefined());
  });

  test("empty states and the load error", async () => {
    serve(mockFetch, { "GET /api/v1/contracts?": { status: 200, body: { ...CONTRACT_PAGE, items: [], total: 0 } } });
    const { unmount } = renderWithProviders(<Contracts />);
    expect(await screen.findByText("No contracts yet — create one or import a document")).toBeInTheDocument();
    unmount();
    serve(mockFetch, { "GET /api/v1/contracts?": { status: 500, body: { title: "x", status: 500 } } });
    renderWithProviders(<Contracts />);
    expect(await screen.findByText("Could not load the contracts")).toBeInTheDocument();
    expect(screen.getByText("Load failed (500)")).toBeInTheDocument();
  });
});
