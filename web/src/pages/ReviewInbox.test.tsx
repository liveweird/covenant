import { QueryClient } from "@tanstack/react-query";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { calledUrl, signIn, type FetchMock } from "../test/contractsFixtures";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import ReviewInbox from "./ReviewInbox";

const ROW = {
  id: 41,
  contract: {
    id: 5, name: "orders-api", type: "OPENAPI", system: { id: 7, name: "gateway" }, domain: { id: 1, name: "Payments" },
    owner: { kind: "TEAM", id: 3, name: "Payments Team", deleted: false }, canWrite: false,
  },
  version: { id: 11, version: "1.2.0", contentRevision: 3 },
  review: {
    status: "OPEN", contentRevision: 3, closeReason: null, requestedBy: { id: 2, name: "Ada Owner", deleted: false },
    requestedAt: 1_700_000_000_000, approvalCount: 1, changesRequestedCount: 1, myDecision: null,
  },
  subscribed: true, owned: false, awaitingMyReview: true, changesRequested: true, needsNewReview: false, canRequest: false,
};
const PAGE = { items: [ROW], page: 1, pageSize: 20, total: 1 };
const SUMMARY = { total: 1, awaitingMyReview: 1, changesRequested: 1, needsNewReview: 0 };

describe("ReviewInbox", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    signIn([], 7);
    mockFetch = vi.fn((url: string) => {
      if (url.startsWith("/api/v1/version-reviews/inbox/summary?")) return Promise.resolve(jsonResponse(200, SUMMARY));
      if (url.startsWith("/api/v1/version-reviews/inbox?")) return Promise.resolve(jsonResponse(200, PAGE));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    vi.stubGlobal("fetch", mockFetch);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("shows the latest review as a document-first link with accessible summary filters", async () => {
    renderWithProviders(<ReviewInbox />, { route: "/reviews" });
    expect(await screen.findByRole("heading", { level: 2, name: "Review inbox" })).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Review inbox" });
    expect(await within(table).findByText("orders-api")).toBeInTheDocument();
    expect(within(table).getByText("Awaiting my decision")).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: "Review orders-api 1.2.0" })).toHaveAttribute("href", "/contracts/5/versions/11#reviews");
    expect(within(table).getByRole("link", { name: "Review orders-api 1.2.0" })).toHaveTextContent("Review");
    expect(screen.getByRole("button", { name: "Refresh" })).toBeInTheDocument();
    expect(within(table).queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    for (const label of ["All reviews", "Awaiting my decision", "Changes requested", "Needs new review"]) {
      expect(screen.getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  test("hydrates q, scope and attention from the URL and lifts attention from summary", async () => {
    renderWithProviders(<ReviewInbox />, { route: "/reviews?q=orders&scope=ALL&attention=AWAITING_MY_REVIEW" });
    await screen.findByRole("table", { name: "Review inbox" });
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/version-reviews/inbox?") && url.includes("q=orders") && url.includes("scope=ALL") && url.includes("attention=AWAITING_MY_REVIEW"))).toBeDefined());
    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/version-reviews/inbox/summary?") && url.includes("q=orders") && url.includes("scope=ALL") && !url.includes("attention="))).toBeDefined();
    await userEvent.setup().click(screen.getByRole("button", { name: /filters/i }));
    expect(screen.getByLabelText("Search contracts or versions")).toHaveValue("orders");
    expect(screen.getByLabelText("Review scope", { selector: "input" })).toHaveValue("All contracts");
  });

  test("offers all four scopes and attention cards update only the list attention", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReviewInbox />, { route: "/reviews" });
    await screen.findByRole("table", { name: "Review inbox" });
    await user.click(screen.getByRole("button", { name: /filters/i }));
    await user.click(screen.getByLabelText("Review scope", { selector: "input" }));
    for (const label of ["Relevant to me", "Owned", "Followed", "All contracts"]) expect(await screen.findByRole("option", { name: label })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(screen.getByRole("button", { name: "Needs new review" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/version-reviews/inbox?") && url.includes("attention=NEEDS_NEW_REVIEW"))).toBeDefined());
    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/version-reviews/inbox/summary?") && url.includes("attention="))).toBeUndefined();
  });

  test("keys personal reads by caller so a changed session cannot reuse another inbox", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const first = renderWithProviders(<ReviewInbox />, { route: "/reviews", queryClient });
    await screen.findByRole("table", { name: "Review inbox" });
    expect(queryClient.getQueryCache().getAll().some((query) => query.queryKey.includes(7))).toBe(true);
    first.unmount();
    signIn([], 8);
    renderWithProviders(<ReviewInbox />, { route: "/reviews", queryClient });
    await waitFor(() => expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/version-reviews/inbox?"))).toHaveLength(2));
    expect(queryClient.getQueryCache().getAll().some((query) => query.queryKey.includes(8))).toBe(true);
  });

  test("never displays the previous caller's rows or summary while a changed caller loads", async () => {
    let finishList!: () => void;
    let finishSummary!: () => void;
    const nextList = new Promise<Response>((resolve) => {
      finishList = () => resolve(jsonResponse(200, { ...PAGE, items: [{ ...ROW, id: 52, contract: { ...ROW.contract, name: "inventory-api" } }] }));
    });
    const nextSummary = new Promise<Response>((resolve) => {
      finishSummary = () => resolve(jsonResponse(200, { ...SUMMARY, total: 2 }));
    });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const view = renderWithProviders(<ReviewInbox />, { route: "/reviews", queryClient });
    expect(await screen.findByText("orders-api")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "All reviews" })).toHaveTextContent("1");

    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/version-reviews/inbox/summary?")) return nextSummary;
      if (url.startsWith("/api/v1/version-reviews/inbox?")) return nextList;
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    signIn([], 8);
    view.rerender(<ReviewInbox />);

    await waitFor(() => expect(screen.queryByText("orders-api")).not.toBeInTheDocument());
    expect(screen.getByRole("button", { name: "All reviews" })).toHaveTextContent("—");
    finishList();
    finishSummary();
    expect(await screen.findByText("inventory-api")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "All reviews" })).toHaveTextContent("2");
  });

  test("keeps attention controls usable when the summary fails", async () => {
    mockFetch.mockImplementation((url: string) => url.startsWith("/api/v1/version-reviews/inbox/summary?")
      ? Promise.resolve(jsonResponse(500, { title: "Internal Server Error", status: 500 }))
      : Promise.resolve(jsonResponse(200, PAGE)));
    const user = userEvent.setup();
    renderWithProviders(<ReviewInbox />, { route: "/reviews?attention=NEEDS_NEW_REVIEW" });
    expect(await screen.findByText("Could not load the review inbox summary")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Needs new review" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "All reviews" })).toHaveTextContent("—");
    await user.click(screen.getByRole("button", { name: "All reviews" }));
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/version-reviews/inbox?") && !url.includes("attention="))).toBeDefined());
  });

  test("a failed list is an error and never masquerades as an empty inbox", async () => {
    mockFetch.mockImplementation((url: string) => url.startsWith("/api/v1/version-reviews/inbox/summary?")
      ? Promise.resolve(jsonResponse(200, SUMMARY))
      : Promise.resolve(jsonResponse(500, { title: "Internal Server Error", status: 500 })));
    renderWithProviders(<ReviewInbox />, { route: "/reviews" });
    expect(await screen.findByText("Could not load the review inbox")).toBeInTheDocument();
    expect(screen.queryByText("No review items match these filters.")).not.toBeInTheDocument();
  });

  test("refreshes the list and summary together", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ReviewInbox />, { route: "/reviews" });
    await screen.findByRole("table", { name: "Review inbox" });
    await user.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => {
      expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/version-reviews/inbox?")).length).toBeGreaterThanOrEqual(2);
      expect(mockFetch.mock.calls.filter(([url]) => String(url).startsWith("/api/v1/version-reviews/inbox/summary?")).length).toBeGreaterThanOrEqual(2);
    });
  });
});
