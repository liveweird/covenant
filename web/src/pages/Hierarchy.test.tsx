import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import Hierarchy from "./Hierarchy";
import { calledUrl, serve, signIn, TREE, type FetchMock } from "../test/contractsFixtures";

describe("Hierarchy page", () => {
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

  test("nests domain → system → contract, folds a branch, and expands/collapses everything", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/tree": { status: 200, body: TREE }, "GET /api/v1/contracts/tree?": { status: 200, body: TREE } });
    const user = userEvent.setup();
    renderWithProviders(<Hierarchy />);
    expect(await screen.findByRole("heading", { level: 2, name: "Hierarchy" })).toBeInTheDocument();
    const tree = await screen.findByRole("tree", { name: "Contract hierarchy" });
    expect(tree).toBeInTheDocument();
    expect(screen.getByText("Payments")).toBeInTheDocument();
    expect(screen.getByText("gateway")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open contract orders-api" })).toHaveAttribute("href", "/contracts/5");
    expect(screen.getByText("No systems in this domain")).toBeInTheDocument();
    expect(screen.getAllByText("1 contracts")).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Toggle gateway" }));
    expect(screen.queryByRole("link", { name: "Open contract orders-api" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Expand all" }));
    expect(screen.getByRole("link", { name: "Open contract orders-api" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collapse all" }));
    expect(screen.queryByText("gateway")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Toggle Payments" })).toHaveAttribute("aria-expanded", "false");
  });

  test("the filters narrow the tree request; the empty and error states render", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/tree": { status: 200, body: { domains: [] } }, "GET /api/v1/contracts/tree?": { status: 200, body: { domains: [] } } });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<Hierarchy />);
    expect(await screen.findByText(/No domains yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.type(screen.getByLabelText("Search"), "pay");
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/tree?") && u.includes("q=pay"))).toBeDefined());
    unmount();
    serve(mockFetch, { "GET /api/v1/contracts/tree": { status: 503, body: { title: "x", status: 503 } }, "GET /api/v1/contracts/tree?": { status: 503, body: { title: "x", status: 503 } } });
    renderWithProviders(<Hierarchy />);
    expect(await screen.findByText("Could not load the hierarchy")).toBeInTheDocument();
  });

  test("Hide empty prunes empty systems/domains and persists, toggling back restores them", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/tree": { status: 200, body: TREE }, "GET /api/v1/contracts/tree?": { status: 200, body: TREE } });
    const user = userEvent.setup();
    renderWithProviders(<Hierarchy />);
    expect(await screen.findByText("Identity")).toBeInTheDocument();
    expect(screen.getByText("No systems in this domain")).toBeInTheDocument();
    expect(screen.getByText("gateway")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open contract orders-api" })).toBeInTheDocument();

    const toggle = screen.getByRole("switch", { name: "Hide empty" });
    await user.click(toggle);
    expect(screen.queryByText("Identity")).not.toBeInTheDocument();
    expect(screen.queryByText("No systems in this domain")).not.toBeInTheDocument();
    expect(screen.getByText("gateway")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open contract orders-api" })).toBeInTheDocument();
    expect(localStorage.getItem("covenant.viewSettings.hierarchy.hideEmpty")).toBe("true");

    await user.click(toggle);
    expect(screen.getByText("Identity")).toBeInTheDocument();
    expect(screen.getByText("No systems in this domain")).toBeInTheDocument();
  });

  test("Hide empty shows the all-empty state when every branch is pruned away", async () => {
    const allEmptyTree = { domains: [{ id: 1, name: "Payments", systems: [{ id: 7, name: "gateway", contracts: [] }] }] };
    serve(mockFetch, { "GET /api/v1/contracts/tree": { status: 200, body: allEmptyTree }, "GET /api/v1/contracts/tree?": { status: 200, body: allEmptyTree } });
    const user = userEvent.setup();
    renderWithProviders(<Hierarchy />);
    expect(await screen.findByText("Payments")).toBeInTheDocument();
    await user.click(screen.getByRole("switch", { name: "Hide empty" }));
    expect(screen.queryByText("Payments")).not.toBeInTheDocument();
    expect(screen.getByText(/Every domain and system is empty for these filters/)).toBeInTheDocument();
  });
});
