import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";
import ContractToadieUsage from "./ContractToadieUsage";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const CACHE = { state: "STALE", lastAttemptAt: 20, lastSuccessAt: 10, refreshing: false, lastErrorCode: "UPSTREAM_UNAVAILABLE" };
const LINKS = {
  contractId: 5, connection: { id: 2, name: "Architecture", browserUrl: "https://toadie.example.com" }, cache: CACHE,
  items: [{ id: 9, connectionId: 2, apiEntityId: "api-9", identifier: "orders", title: "Orders API", url: "https://toadie.example.com/entities/api-9/edit", status: "AVAILABLE" }],
};
const USAGE = {
  items: [{ id: "service-1", identifier: "checkout", title: "Checkout", url: "https://toadie.example.com/entities/service-1/edit", roles: ["PROVIDER", "CONSUMER"], providedApiEntityIds: ["api-9"], consumedApiEntityIds: ["api-10"], systems: [{ entityId: "system-1", identifier: "commerce", title: "Commerce", url: "https://toadie.example.com/entities/system-1/edit" }], teams: [{ entityId: "team-1", identifier: "payments", title: "Payments", url: null }], version: null, releaseLine: null }],
  page: 1, pageSize: 20, total: 1, connection: LINKS.connection, cache: CACHE,
};

describe("Contract Toadie usage", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    localStorage.setItem("covenant.auth.token", "token");
    mockFetch = vi.fn((url: string) => {
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, LINKS));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    vi.stubGlobal("fetch", mockFetch);
  });
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  test("a stale refresh error preserves usage and marks unknown version fields explicitly", async () => {
    renderWithProviders(<ContractToadieUsage contractId={5} canWrite={false} />);
    expect(await screen.findByText("Checkout")).toBeInTheDocument();
    expect(screen.getByText(/last refresh failed.*UPSTREAM_UNAVAILABLE/i)).toBeInTheDocument();
    expect(screen.getByText(/Last successful refresh:/)).toBeInTheDocument();
    expect(screen.getAllByText("Unknown")).toHaveLength(2);
    expect(screen.getByRole("link", { name: "Open Checkout in Toadie" })).toHaveAttribute("href", "https://toadie.example.com/entities/service-1/edit");
    expect(screen.queryByRole("button", { name: "Edit Toadie links" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Refresh usage" })).not.toBeInTheDocument();
  });

  test("selected APIs survive result paging, and a missing existing API remains removable", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/contracts/5/toadie-links" && init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, { ...LINKS, items: [{ ...LINKS.items[0], status: "MISSING" }] }));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      if (url.startsWith("/api/v1/toadie-connections?")) return Promise.resolve(jsonResponse(200, { items: [{ id: 2, name: "Architecture" }], page: 1, pageSize: 20, total: 1 }));
      if (url.includes("/toadie-connections/2/apis?") && url.includes("page=2")) return Promise.resolve(jsonResponse(200, { items: [{ entityId: "api-2", identifier: "billing", title: "Billing API", url: null }], page: 2, pageSize: 20, total: 21 }));
      if (url.includes("/toadie-connections/2/apis?")) return Promise.resolve(jsonResponse(200, { items: [{ entityId: "api-1", identifier: "checkout", title: "Checkout API", url: null }], page: 1, pageSize: 20, total: 21 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ContractToadieUsage contractId={5} canWrite />);
    await user.click(await screen.findByRole("button", { name: "Edit Toadie links" }));
    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByRole("button", { name: "Remove Orders API" })).toHaveTextContent("Missing in the current snapshot");
    await user.click(await within(dialog).findByRole("checkbox", { name: "Checkout API" }));
    await user.click(within(dialog).getByRole("button", { name: "Next page" }));
    await user.click(await within(dialog).findByRole("checkbox", { name: "Billing API" }));
    expect(within(dialog).getByText("Selected (3)")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Save links" }));
    await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, request]) => url === "/api/v1/contracts/5/toadie-links" && (request as RequestInit)?.method === "PUT");
      expect(JSON.parse((call?.[1] as RequestInit).body as string)).toEqual({ connectionId: 2, apiEntityIds: ["api-9", "api-1", "api-2"] });
    });
  });

  test("an accepted refresh refetches both status sources so polling can follow the queued job", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/contracts/5/toadie-usage/refresh" && init?.method === "POST") return Promise.resolve(new Response(null, { status: 202 }));
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, { ...LINKS, cache: { ...CACHE, state: "CURRENT", lastErrorCode: null } }));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, { ...USAGE, cache: { ...CACHE, state: "CURRENT", lastErrorCode: null } }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ContractToadieUsage contractId={5} canWrite />);
    await user.click(await screen.findByRole("button", { name: "Refresh usage" }));
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => url === "/api/v1/contracts/5/toadie-links")).toHaveLength(2));
    expect(mockFetch.mock.calls.filter(([url]) => typeof url === "string" && url.startsWith("/api/v1/contracts/5/toadie-usage?")).length).toBeGreaterThanOrEqual(2);
  });

  test("refresh completion invalidates lifecycle overview aggregates", async () => {
    const invalidate = vi.spyOn(QueryClient.prototype, "invalidateQueries");
    let linksCalls = 0;
    let usageCalls = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/contracts/5/toadie-usage/refresh" && init?.method === "POST") return Promise.resolve(new Response(null, { status: 202 }));
      if (url === "/api/v1/contracts/5/toadie-links") {
        linksCalls += 1;
        const refreshing = linksCalls === 2;
        return Promise.resolve(jsonResponse(200, { ...LINKS, cache: { ...CACHE, state: refreshing ? "STALE" : "CURRENT", refreshing, lastErrorCode: null } }));
      }
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) {
        usageCalls += 1;
        const refreshing = usageCalls === 2;
        return Promise.resolve(jsonResponse(200, { ...USAGE, cache: { ...CACHE, state: refreshing ? "STALE" : "CURRENT", refreshing, lastErrorCode: null } }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ContractToadieUsage contractId={5} canWrite />);
    await user.click(await screen.findByRole("button", { name: "Refresh usage" }));
    await waitFor(() => expect(usageCalls).toBe(2));
    await waitFor(() => expect(usageCalls).toBeGreaterThanOrEqual(3), { timeout: 3000 });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["contracts", "lifecycle-overview"] });
    invalidate.mockRestore();
  });

  test("a disconnected link remains removable when no active connections exist and clears with a valid request", async () => {
    const disconnectedLinks = {
      ...LINKS,
      connection: null,
      cache: { ...CACHE, state: "DISCONNECTED", lastErrorCode: null },
      items: [{ ...LINKS.items[0], status: "DISCONNECTED", url: null }],
    };
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url === "/api/v1/contracts/5/toadie-links" && init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, disconnectedLinks));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, { ...USAGE, items: [], total: 0, connection: null, cache: disconnectedLinks.cache }));
      if (url.startsWith("/api/v1/toadie-connections?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ContractToadieUsage contractId={5} canWrite />);
    await user.click(await screen.findByRole("button", { name: "Edit Toadie links" }));
    const dialog = screen.getByRole("dialog");
    const save = within(dialog).getByRole("button", { name: "Save links" });
    expect(within(dialog).getByRole("button", { name: "Remove Orders API" })).toHaveTextContent("Disconnected");
    expect(save).toBeDisabled();
    await user.click(within(dialog).getByRole("button", { name: "Remove Orders API" }));
    expect(save).toBeEnabled();
    await user.click(save);
    await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, request]) => url === "/api/v1/contracts/5/toadie-links" && (request as RequestInit)?.method === "PUT");
      expect(JSON.parse((call?.[1] as RequestInit).body as string)).toEqual({ connectionId: null, apiEntityIds: [] });
    });
    expect(mockFetch.mock.calls.some(([url]) => typeof url === "string" && url.includes("/toadie-connections/NaN/apis"))).toBe(false);
  });
});
