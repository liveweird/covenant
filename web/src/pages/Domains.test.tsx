import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import Domains from "./Domains";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "covenant.auth.token";
const ROLES_KEY = "covenant.auth.roles";
type FetchMock = ReturnType<typeof vi.fn>;

const PAGE = {
  items: [
    { id: 1, name: "Payments", description: "Money", systemCount: 2, createdAt: 1, updatedAt: 2 },
    { id: 2, name: "Identity", description: null, systemCount: 0, createdAt: 1, updatedAt: 2 },
  ],
  page: 1,
  pageSize: 20,
  total: 2,
};

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}
const findCall = (mockFetch: FetchMock, method: string, url: string) =>
  mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);

describe("Domains page", () => {
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

  test("a regular user gets the read-only table", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderWithProviders(<Domains />);
    expect(await screen.findByText("Payments")).toBeInTheDocument();
    expect(screen.getByText("Money")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new domain/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
  });

  test("an admin creates a domain through the modal", async () => {
    serve(mockFetch, { "POST /api/v1/domains": { status: 201, body: { ...PAGE.items[1], id: 9, name: "Ledger" } } });
    const user = userEvent.setup();
    renderWithProviders(<Domains />);
    await user.click(await screen.findByRole("button", { name: /new domain/i }));
    const modal = screen.getByRole("dialog");
    await user.type(within(modal).getByLabelText("Name"), " Ledger ");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/domains")).toBeDefined());
    expect(JSON.parse((findCall(mockFetch, "POST", "/api/v1/domains")![1] as RequestInit).body as string)).toEqual({ name: "Ledger", description: null });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });

  test("validation blocks an empty name and a 409 marks the field", async () => {
    serve(mockFetch, { "POST /api/v1/domains": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderWithProviders(<Domains />);
    await user.click(await screen.findByRole("button", { name: /new domain/i }));
    const modal = screen.getByRole("dialog");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("Name must be 1–100 characters")).toBeInTheDocument();
    await user.type(within(modal).getByLabelText("Name"), "Payments");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("A domain with this name already exists")).toBeInTheDocument();
  });

  test("an admin edits (prefilled PUT) and deletes; a 409 on delete names the systems", async () => {
    serve(mockFetch, {
      "PUT /api/v1/domains/2": { status: 204 },
      "DELETE /api/v1/domains/1": { status: 409, body: { title: "Conflict", status: 409 } },
    });
    const user = userEvent.setup();
    renderWithProviders(<Domains />);
    await user.click(await screen.findByRole("button", { name: "Operations for Identity" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit Identity" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByLabelText("Name")).toHaveValue("Identity");
    await user.type(within(modal).getByLabelText("Description"), "who you are");
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/domains/2")).toBeDefined());
    expect(JSON.parse((findCall(mockFetch, "PUT", "/api/v1/domains/2")![1] as RequestInit).body as string)).toEqual({ name: "Identity", description: "who you are" });
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: "Operations for Payments" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete Payments" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    expect(await screen.findByText(/still holds systems/)).toBeInTheDocument();
  });

  test("shows the load-failure alert", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(500, { title: "boom", status: 500 })));
    renderWithProviders(<Domains />);
    expect(await screen.findByText("Could not load the domains")).toBeInTheDocument();
  });
});
