import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { calledUrl, serve, signIn, type FetchMock } from "../test/contractsFixtures";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import LifecycleOverview from "./LifecycleOverview";

const CACHE = { state: "CURRENT", lastAttemptAt: 1_700_000_000_000, lastSuccessAt: 1_700_000_000_000, refreshing: false, lastErrorCode: null };
const ROW = {
  id: 21,
  contract: {
    id: 5, name: "orders-api", type: "OPENAPI", system: { id: 7, name: "gateway" }, domain: { id: 1, name: "Payments" },
    owner: { kind: "TEAM", id: 3, name: "Payments Team", deleted: false }, canWrite: false,
  },
  major: 1, supportStatus: "MAINTENANCE", deprecatesOn: "2026-10-01", supportEndsOn: "2026-11-01",
  replacement: null, hasMigrationGuide: false, nextDeadline: "2026-10-01", deadlineSoon: true, supportEnded: false,
  migrationIncomplete: true, usageUncertain: false,
  usage: { consumerCount: 0, providerCount: 1, cache: CACHE, unavailableLinkCount: 0 },
};
const UNKNOWN_ROW = {
  ...ROW, id: 22, major: 2, supportStatus: "UNSPECIFIED", deprecatesOn: null, supportEndsOn: null, nextDeadline: null,
  deadlineSoon: false, migrationIncomplete: false, usageUncertain: true,
  usage: { consumerCount: null, providerCount: null, cache: { ...CACHE, state: "UNLINKED", lastSuccessAt: null }, unavailableLinkCount: 0 },
};
const EOL_ROW = {
  ...ROW, id: 23, major: 3, supportStatus: "END_OF_LIFE", deprecatesOn: "2025-01-01", supportEndsOn: "2025-06-01", nextDeadline: null,
  deadlineSoon: false, supportEnded: false, migrationIncomplete: false, hasMigrationGuide: false,
  replacement: { contractId: 8, contractName: "orders-v4", major: 4, available: false },
};
const PAGE = { items: [ROW, UNKNOWN_ROW, EOL_ROW], page: 1, pageSize: 20, total: 3 };
const SUMMARY = { asOfDate: "2026-09-20", total: 2, deadlineSoon: 1, supportEnded: 0, migrationIncomplete: 1, usageUncertain: 1, ownerUser: [{ id: 9, name: "Ada Reader", count: 1 }] };

describe("Lifecycle overview", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn([], 2);
    serve(mockFetch, {
      "GET /api/v1/contracts/lifecycle-overview?": { status: 200, body: PAGE },
      "GET /api/v1/contracts/lifecycle-overview/summary": { status: 200, body: SUMMARY },
      "GET /api/v1/contracts/lifecycle-overview/summary?": { status: 200, body: SUMMARY },
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows one row per major and distinguishes a genuine zero from unavailable usage", async () => {
    renderWithProviders(<LifecycleOverview />);
    await screen.findByText("1.x");
    const table = await screen.findByRole("table", { name: "Lifecycle overview" });
    expect(table.closest<HTMLElement>('[style*="--table-min-width"]')).toHaveStyle("--table-min-width: 77.5rem");
    expect(within(table).getByText("1.x")).toBeInTheDocument();
    expect(within(table).getByText("2.x")).toBeInTheDocument();
    const eolRow = within(table).getByText("3.x").closest("tr")!;
    expect(within(eolRow).getByText(/Deprecation Jan 1, 2025/)).toBeInTheDocument();
    expect(within(eolRow).getByText(/Support ends Jun 1, 2025/)).toBeInTheDocument();
    expect(within(eolRow).getByText("Plan recorded")).toBeInTheDocument();
    expect(within(eolRow).getByText("Unavailable contract #8 · 4.x")).toBeInTheDocument();
    expect(within(eolRow).queryByText("Support-end date reached")).not.toBeInTheDocument();
    expect(within(table).getAllByText("0 consumer services")).toHaveLength(2);
    expect(within(table).getByText(/Deprecation Oct 1, 2026/)).toBeInTheDocument();
    expect(within(table).getByText(/Support ends Nov 1, 2026/)).toBeInTheDocument();
    expect(within(table).getByText("Unavailable")).toBeInTheDocument();
    expect(within(table).getByText("No dates set")).toBeInTheDocument();
    expect(screen.getAllByRole("button", { name: /Review impact for orders-api/ })).toHaveLength(3);
    expect(screen.queryByRole("button", { name: /Edit policy for orders-api/ })).not.toBeInTheDocument();
  });

  test("summary counts are filters and the summary request lifts attention", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LifecycleOverview />);
    await user.click(await screen.findByRole("button", { name: /Deadlines in next 30 days/ }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.includes("/lifecycle-overview?") && url.includes("attention=DEADLINE_SOON"))).toBeDefined());
    const summaryCall = calledUrl(mockFetch, "GET", (url) => url.includes("/lifecycle-overview/summary") && !url.includes("attention="));
    expect(summaryCall).toBeDefined();
  });

  test("no-date and owner-user choices are discoverable to a normal reader", async () => {
    const user = userEvent.setup();
    renderWithProviders(<LifecycleOverview />);
    await screen.findAllByText("orders-api");
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.click(screen.getByLabelText("Deadline", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "No dates set" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.includes("/lifecycle-overview?") && url.includes("deadline=NONE"))).toBeDefined());
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Ada Reader (1)" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.includes("ownerUserId=9"))).toBeDefined());
    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/users?"))).toBeUndefined();
  });

  test("a slower policy fetch cannot replace a newer row selection", async () => {
    const writerA = { ...ROW, contract: { ...ROW.contract, canWrite: true } };
    const writerB = { ...ROW, id: 31, major: 2, contract: { ...ROW.contract, id: 6, name: "billing-api", canWrite: true } };
    let resolveA!: (response: Response) => void;
    let resolveB!: (response: Response) => void;
    const responseA = new Promise<Response>((resolve) => { resolveA = resolve; });
    const responseB = new Promise<Response>((resolve) => { resolveB = resolve; });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/contracts/lifecycle-overview/summary")) return Promise.resolve(new Response(JSON.stringify(SUMMARY), { status: 200 }));
      if (url.startsWith("/api/v1/contracts/lifecycle-overview?")) return Promise.resolve(new Response(JSON.stringify({ ...PAGE, items: [writerA, writerB], total: 2 }), { status: 200 }));
      if (url === "/api/v1/contracts/5/release-lines/1") return responseA;
      if (url === "/api/v1/contracts/6/release-lines/2" && init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/contracts/6/release-lines/2") return responseB;
      if (url.startsWith("/api/v1/contracts/6/versions?")) return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200 }));
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200 }));
      if (url.startsWith("/api/v1/systems?")) return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200 }));
      if (url.startsWith("/api/v1/teams?")) return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200 }));
      return Promise.resolve(new Response(JSON.stringify({ title: "Not Found", status: 404 }), { status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<LifecycleOverview />);
    await user.click(await screen.findByRole("button", { name: "Edit policy for orders-api 1.x" }));
    await user.click(screen.getByRole("button", { name: "Edit policy for billing-api 2.x" }));
    resolveB(new Response(JSON.stringify({ id: 31, contractId: 6, major: 2, supportStatus: "SUPPORTED", supportEndsOn: null, supportPolicy: "Billing policy", recommendedVersionId: null, deprecatesOn: null, replacement: null, migrationGuide: null, latestVersion: null, recommendedVersion: null, versionCount: 1, updatedAt: 1 }), { status: 200 }));
    expect(await screen.findByRole("dialog", { name: "Edit policy for 2.x" })).toBeInTheDocument();
    resolveA(new Response(JSON.stringify({ id: 21, contractId: 5, major: 1, supportStatus: "SUPPORTED", supportEndsOn: null, supportPolicy: "Orders policy", recommendedVersionId: null, deprecatesOn: null, replacement: null, migrationGuide: null, latestVersion: null, recommendedVersion: null, versionCount: 1, updatedAt: 1 }), { status: 200 }));
    await Promise.resolve();
    expect(screen.getByRole("dialog", { name: "Edit policy for 2.x" })).toBeInTheDocument();
    expect(screen.getByLabelText("Support policy")).toHaveValue("Billing policy");
    await user.click(screen.getByRole("button", { name: "Save policy" }));
    await waitFor(() => expect(mockFetch.mock.calls.some(([url, request]) => url === "/api/v1/contracts/6/release-lines/2" && (request as RequestInit)?.method === "PUT")).toBe(true));
    expect(mockFetch.mock.calls.some(([url, request]) => url === "/api/v1/contracts/5/release-lines/1" && (request as RequestInit)?.method === "PUT")).toBe(false);
  });

  test("the overview keeps polling an in-flight usage refresh after its dialog closes", async () => {
    let listCalls = 0;
    let summaryCalls = 0;
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/contracts/lifecycle-overview/summary")) {
        summaryCalls += 1;
        return Promise.resolve(new Response(JSON.stringify({ ...SUMMARY, usageUncertain: summaryCalls === 1 ? 1 : 0 }), { status: 200 }));
      }
      if (url.startsWith("/api/v1/contracts/lifecycle-overview?")) {
        listCalls += 1;
        const refreshing = listCalls === 1;
        const row = { ...ROW, usageUncertain: refreshing, usage: { ...ROW.usage, consumerCount: refreshing ? null : 2, cache: { ...CACHE, refreshing } } };
        return Promise.resolve(new Response(JSON.stringify({ ...PAGE, items: [row], total: 1 }), { status: 200 }));
      }
      if (url.startsWith("/api/v1/domains?") || url.startsWith("/api/v1/systems?") || url.startsWith("/api/v1/teams?")) {
        return Promise.resolve(new Response(JSON.stringify({ items: [], page: 1, pageSize: 100, total: 0 }), { status: 200 }));
      }
      return Promise.resolve(new Response(JSON.stringify({ title: "Not Found", status: 404 }), { status: 404 }));
    });
    renderWithProviders(<LifecycleOverview />);
    expect(await screen.findByText("Unavailable")).toBeInTheDocument();
    await waitFor(() => expect(listCalls).toBeGreaterThanOrEqual(2), { timeout: 3000 });
    await waitFor(() => expect(summaryCalls).toBeGreaterThanOrEqual(2));
    expect(screen.getByText("2 consumer services")).toBeInTheDocument();
  });
});
