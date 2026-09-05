import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import Environments from "./Environments";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "covenant.auth.token";
const ROLES_KEY = "covenant.auth.roles";
type FetchMock = ReturnType<typeof vi.fn>;

const SYSTEMS = { items: [{ id: 7, domainId: 1, domainName: "Payments", name: "gateway", description: null, contractCount: 1, createdAt: 1, updatedAt: 2 }], page: 1, pageSize: 100, total: 1 };
const STAGING = {
  id: 4, systemId: 7, systemName: "gateway", name: "staging", description: "The staging cluster", httpBaseUrl: "http://gw.internal:8080",
  kafka: null, postgres: { jdbcUrl: "jdbc:postgresql://db.internal:5432/app", username: "reader", hasPassword: true }, createdAt: 1, updatedAt: 2,
};
const PAGE = { items: [STAGING], page: 1, pageSize: 20, total: 1 };

function serve(mockFetch: FetchMock, mutations: Record<string, { status: number; body?: unknown }> = {}) {
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const m = mutations[`${method} ${url}`];
    if (m) return Promise.resolve(m.body === undefined ? new Response(null, { status: m.status }) : jsonResponse(m.status, m.body));
    if (method === "GET" && url.startsWith("/api/v1/systems?")) return Promise.resolve(jsonResponse(200, SYSTEMS));
    if (method === "GET" && url.startsWith("/api/v1/environments?")) return Promise.resolve(jsonResponse(200, PAGE));
    return Promise.resolve(jsonResponse(404, { title: "x", status: 404 }));
  });
}
const findCall = (mockFetch: FetchMock, method: string, url: string) =>
  mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
const bodyOf = (call: unknown[] | undefined) => JSON.parse((call![1] as RequestInit).body as string);

describe("Environments page", () => {
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

  test("rows show the system and the target badges; a regular user has no controls", async () => {
    localStorage.setItem(ROLES_KEY, "[]");
    serve(mockFetch);
    renderWithProviders(<Environments />);
    expect(await screen.findByText("staging")).toBeInTheDocument();
    expect(screen.getByText("gateway")).toBeInTheDocument();
    expect(screen.getByText("HTTP")).toBeInTheDocument();
    expect(screen.getByText("PostgreSQL")).toBeInTheDocument();
    expect(screen.queryByText("Kafka")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /new environment/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /operations for/i })).not.toBeInTheDocument();
  });

  test("an admin creates an environment with an HTTP and a PostgreSQL target", async () => {
    serve(mockFetch, { "POST /api/v1/environments": { status: 201, body: { ...STAGING, id: 9, name: "prod" } } });
    const user = userEvent.setup();
    renderWithProviders(<Environments />);
    await user.click(await screen.findByRole("button", { name: /new environment/i }));
    const modal = screen.getByRole("dialog");
    fireEvent.click(within(modal).getByLabelText("System", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Payments / gateway" }));
    await user.type(within(modal).getByLabelText("Name"), "prod");
    await user.type(within(modal).getByLabelText("Base URL"), "http://gw.internal:8080");
    await user.click(within(modal).getByLabelText("PostgreSQL target (ODCS)"));
    await user.type(within(modal).getByLabelText("JDBC URL"), "jdbc:postgresql://db.internal:5432/app");
    await user.type(within(modal).getByLabelText("Username"), "reader");
    await user.type(within(modal).getByLabelText("Password"), "s3cret");
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/environments")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/environments"))).toEqual({
      systemId: 7, name: "prod", description: null, httpBaseUrl: "http://gw.internal:8080", kafka: null,
      postgres: { jdbcUrl: "jdbc:postgresql://db.internal:5432/app", username: "reader", password: "s3cret" },
    });
  });

  test("validation names the missing pieces, including a password for a new SASL Kafka target", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<Environments />);
    await user.click(await screen.findByRole("button", { name: /new environment/i }));
    const modal = screen.getByRole("dialog");
    await user.click(within(modal).getByLabelText("HTTP target (OpenAPI)"));
    await user.click(within(modal).getByLabelText("Kafka target (AsyncAPI)"));
    fireEvent.click(within(modal).getByLabelText("Security protocol", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "SASL_SSL" }));
    await user.click(within(modal).getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("Pick a system")).toBeInTheDocument();
    expect(screen.getByText("Name must be 1–50 characters")).toBeInTheDocument();
    expect(screen.getByText("Use 1–20 comma-separated host:port entries")).toBeInTheDocument();
    expect(screen.getByText("Pick a SASL mechanism")).toBeInTheDocument();
    expect(screen.getByText("A password is required")).toBeInTheDocument();
  });

  test("editing keeps a stored password when the field stays blank, and a 409 marks the name", async () => {
    serve(mockFetch, { "PUT /api/v1/environments/4": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    renderWithProviders(<Environments />);
    await user.click(await screen.findByRole("button", { name: "Operations for staging" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit staging" }));
    const modal = await screen.findByRole("dialog");
    expect(within(modal).getByText("Leave blank to keep the stored password")).toBeInTheDocument();
    expect(within(modal).getByLabelText("Password")).toHaveValue("");
    await user.click(within(modal).getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/environments/4")).toBeDefined());
    const body = bodyOf(findCall(mockFetch, "PUT", "/api/v1/environments/4"));
    expect(body.postgres).toEqual({ jdbcUrl: "jdbc:postgresql://db.internal:5432/app", username: "reader" });
    expect(await screen.findByText("An environment with this name already exists in this system")).toBeInTheDocument();
  });

  test("the system filter refetches with systemId= and delete confirms", async () => {
    serve(mockFetch, { "DELETE /api/v1/environments/4": { status: 204 } });
    const user = userEvent.setup();
    renderWithProviders(<Environments />);
    await screen.findByText("staging");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    fireEvent.click(screen.getByLabelText("System", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "Payments / gateway" }));
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("systemId=7"))).toBe(true));
    await user.click(screen.getByRole("button", { name: "Operations for staging" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete staging" }));
    expect(await screen.findByText(/"staging" \(gateway\) will be deleted/)).toBeInTheDocument();
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/environments/4")).toBeDefined());
  });
});
