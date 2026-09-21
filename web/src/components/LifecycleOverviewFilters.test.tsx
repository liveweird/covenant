import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { QueryClient } from "@tanstack/react-query";
import { useLifecycleOverviewFilters } from "../hooks/useLifecycleOverviewFilters";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen } from "../test/render";
import LifecycleOverviewFilters from "./LifecycleOverviewFilters";

const page = (prefix: string, domainId?: number) => Array.from({ length: 100 }, (_, index) => ({
  id: index + 1,
  name: `${prefix} ${String(index + 1).padStart(3, "0")}`,
  ...(domainId == null ? {} : { domainId, domainName: "Late domain" }),
  description: null,
  createdAt: 1,
  updatedAt: 1,
}));

function Harness() {
  const filters = useLifecycleOverviewFilters("overviewFilterTest");
  return <LifecycleOverviewFilters filters={filters} summary={{ asOfDate: "2026-09-20", total: 0, deadlineSoon: 0, supportEnded: 0, migrationIncomplete: 0, usageUncertain: 0, ownerUser: [] }} />;
}

describe("Lifecycle overview registry filters", () => {
  beforeEach(() => {
    localStorage.setItem("covenant.auth.token", "token");
    localStorage.setItem("covenant.viewSettings.overviewFilterTest.filter.domain", JSON.stringify("999"));
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      const parsed = new URL(url, "http://localhost");
      const requestPage = Number(parsed.searchParams.get("page"));
      if (parsed.pathname === "/api/v1/domains") {
        const items = requestPage === 1 ? page("Domain") : [{ id: 999, name: "Late domain", description: null, systemCount: 1, createdAt: 1, updatedAt: 1 }];
        return Promise.resolve(jsonResponse(200, { items, page: requestPage, pageSize: 100, total: 101 }));
      }
      if (parsed.pathname === "/api/v1/systems") {
        const items = requestPage === 1 ? page("System", 999) : [{ id: 999, domainId: 999, domainName: "Late domain", name: "Late system", description: null, contractCount: 1, createdAt: 1, updatedAt: 1 }];
        return Promise.resolve(jsonResponse(200, { items, page: requestPage, pageSize: 100, total: 101 }));
      }
      if (parsed.pathname === "/api/v1/teams") {
        const items = requestPage === 1 ? page("Team") : [{ id: 999, name: "Late team", description: null, memberCount: 1, createdAt: 1, updatedAt: 1 }];
        return Promise.resolve(jsonResponse(200, { items, page: requestPage, pageSize: 100, total: 101 }));
      }
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    }));
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a selected domain and registry choices beyond the first 100 remain discoverable", async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    // Legacy page-shaped cache entries must not be reused by all-pages pickers.
    queryClient.setQueryData(["teams", "picker", "all"], { items: [{ id: 1, name: "Cached page team" }], page: 1, pageSize: 100, total: 1 });
    renderWithProviders(<Harness />, { queryClient });
    expect(await screen.findByDisplayValue("Late domain")).toBeInTheDocument();
    await user.click(screen.getByLabelText("System", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Late system" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Late team" })).toBeInTheDocument();
    const calls = vi.mocked(fetch).mock.calls.map(([url]) => String(url));
    expect(calls.some((url) => url.includes("/systems?") && url.includes("page=2") && url.includes("domainId=999"))).toBe(true);
    expect(calls.some((url) => url.includes("/teams?") && url.includes("page=2"))).toBe(true);
  });
});
