import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import Systems from "./Systems";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "covenant.auth.token";
const ROLES_KEY = "covenant.auth.roles";
type FetchMock = ReturnType<typeof vi.fn>;

const DOMAINS = {
  items: [
    { id: 1, name: "Payments", description: null, systemCount: 1, createdAt: 1, updatedAt: 2 },
    { id: 2, name: "Identity", description: null, systemCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  page: 1,
  pageSize: 100,
  total: 2,
};
const PAGE = {
  items: [{ id: 7, domainId: 1, domainName: "Payments", name: "gateway", description: "the edge", contractCount: 0, createdAt: 1, updatedAt: 2 }],
  page: 1,
  pageSize: 20,
  total: 1,
};

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}, domains: unknown = DOMAINS) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, domains));
    if (method === "GET" && url.startsWith("/api/v1/systems?")) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}
const findCall = (mockFetch: FetchMock, method: string, url: string) =>
  mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);

describe("Systems page", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
    localStorage.setItem(ROLES_KEY, JSON.stringify(["ADMIN"]));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("renders rows with the joined domain name; a regular user has no controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderWithProviders(<Systems />);
    expect(await screen.findByText("gateway")).toBeInTheDocument();
    expect(screen.getByText("Payments")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new system/i })).not.toBeInTheDocument();
  });

  test("an admin creates a system in a picked domain", async () => {
    serve(mockFetch, { "POST /api/v1/systems": { status: 201, body: { ...PAGE.items[0], id: 9, name: "ledger" } } });
    const user = userEvent.setup();
    renderWithProviders(<Systems />);
    await user.click(await screen.findByRole("button", { name: /new system/i }));
    const modal = screen.getByRole("dialog");
    fireEvent.click(within(modal).getByLabelText("Domain", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Identity" }));
    await user.type(within(modal).getByLabelText("Name"), "ledger");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/systems")).toBeDefined());
    expect(JSON.parse((findCall(mockFetch, "POST", "/api/v1/systems")![1] as RequestInit).body as string)).toEqual({ domainId: 2, name: "ledger", description: null });
  });

  test("validation requires a domain and a name", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Systems />);
    await user.click(await screen.findByRole("button", { name: /new system/i }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("Pick a domain")).toBeInTheDocument();
    expect(screen.getByText("Name must be 1–100 characters")).toBeInTheDocument();
  });

  test("editing prefills the domain and PUTs the move; a 409 marks the name", async () => {
    serve(mockFetch, { "PUT /api/v1/systems/7": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderWithProviders(<Systems />);
    await user.click(await screen.findByRole("button", { name: "Operations for gateway" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit gateway" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Domain", { selector: "input" })).toHaveValue("Payments");
    expect(screen.getByText("Picking another domain moves the system there")).toBeInTheDocument();
    fireEvent.click(within(modal).getByLabelText("Domain", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Identity" }));
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/systems/7")).toBeDefined());
    expect(JSON.parse((findCall(mockFetch, "PUT", "/api/v1/systems/7")![1] as RequestInit).body as string)).toEqual({ domainId: 2, name: "gateway", description: "the edge" });
    expect(await screen.findByText("A system with this name already exists in this domain")).toBeInTheDocument();
  });

  test("the domain filter refetches with domainId= and delete confirms", async () => {
    serve(mockFetch, { "DELETE /api/v1/systems/7": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Systems />);
    await screen.findByText("gateway");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    fireEvent.click(screen.getByLabelText("Domain", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Payments" }));
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("domainId=1"))).toBe(true));
    await user.click(screen.getByRole("button", { name: "Operations for gateway" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete gateway" }));
    expect(await screen.findByText(/"gateway" \(Payments\) will be deleted/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/systems/7")).toBeDefined());
  });

  test("with no domains the create button is disabled and the empty state says so", async () => {
    serve(mockFetch, {}, { ...DOMAINS, items: [], total: 0 });
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, { ...DOMAINS, items: [], total: 0 }));
      return Promise.resolve(jsonResponse(200, { ...PAGE, items: [], total: 0 }));
    });
    renderWithProviders(<Systems />);
    expect(await screen.findByText("Create a domain first — every system lives inside one")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /new system/i })).toBeDisabled();
  });
});
