import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { useQuery } from "@tanstack/react-query";
import { getVersion } from "../api/versions";
import { bodyOf, findCall, signIn, type FetchMock } from "../test/contractsFixtures";
import { jsonResponse } from "../test/http";
import { renderWithProviders, screen, waitFor } from "../test/render";
import VersionReviews from "./VersionReviews";

const ROUND = {
  id: 41,
  contractId: 5,
  versionId: 11,
  contentRevision: 7,
  contentSha256: "abc",
  requestedBy: { id: 2, name: "Former owner", deleted: true },
  requestedAt: 1_700_000_000_000,
  closedAt: null,
  closeReason: null,
  status: "OPEN",
  isCurrentContent: true,
  approvalCount: 0,
  changesRequestedCount: 0,
  entryCount: 1,
  myDecision: null,
  canComment: true,
  canDecide: true,
};
const ENTRY = {
  id: 61,
  reviewId: 41,
  kind: "COMMENT",
  body: "Please document the failure response.",
  author: { id: 3, name: "Old reviewer", deleted: true },
  createdAt: 1_700_000_100_000,
};

function reviewsPage(overrides: Record<string, unknown> = {}) {
  return { items: [ROUND], page: 1, pageSize: 5, total: 1, canRequest: false, currentContentRevision: 7, ...overrides };
}

function RevisionHarness() {
  const version = useQuery({ queryKey: ["contracts", "version", 5, 11], queryFn: () => getVersion(5, 11) });
  if (!version.data) return null;
  return <VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={version.data.contentRevision} disabled={false} />;
}

describe("VersionReviews", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    signIn();
    mockFetch = vi.fn((url: string) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage()));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [ENTRY], page: 1, pageSize: 10, total: 1 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    vi.stubGlobal("fetch", mockFetch);
  });
  afterEach(() => vi.unstubAllGlobals());

  test("opens the latest round once, shows deleted identities, and preserves a deliberate collapse", async () => {
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    expect(await screen.findByText("Please document the failure response.")).toBeInTheDocument();
    expect(screen.getByText(/Former owner \(deleted\)/)).toBeInTheDocument();
    expect(screen.getByText("Old reviewer (deleted)")).toBeInTheDocument();
    expect(screen.getByText("Newest first")).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([url]) => String(url).includes("sort=-createdAt%2C-id"))).toBe(true);
    const control = screen.getByRole("button", { name: /Review #41/ });
    await user.click(control);
    await waitFor(() => expect(control).toHaveAttribute("aria-expanded", "false"));
    await Promise.resolve();
    expect(control).toHaveAttribute("aria-expanded", "false");
  });

  test("posts the displayed revision and prevents parallel decisions", async () => {
    let posted = false;
    let finishPost!: () => void;
    const pending = new Promise<Response>((resolve) => { finishPost = () => { posted = true; resolve(jsonResponse(201, { ...ENTRY, id: 62, kind: "APPROVED", body: "Looks good" })); }; });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage()));
      if (url.startsWith("/api/v1/version-reviews/41/entries?") && !init?.method) return Promise.resolve(jsonResponse(200, { items: posted ? [{ ...ENTRY, id: 62, kind: "APPROVED", body: "Looks good" }] : [], page: 1, pageSize: 10, total: posted ? 1 : 0 }));
      if (url === "/api/v1/version-reviews/41/entries" && init?.method === "POST") return pending;
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await screen.findByLabelText("Review comment");
    await user.type(screen.getByLabelText("Review comment"), "Looks good");
    await user.click(screen.getByRole("button", { name: "Approve" }));
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/version-reviews/41/entries"))).toEqual({ expectedContentRevision: 7, kind: "APPROVED", body: "Looks good" });
    expect(screen.getByRole("button", { name: "Add comment" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Request changes" })).toBeDisabled();
    finishPost();
    expect(await screen.findByText("Looks good")).toBeInTheDocument();
  });

  test("a displayed/list revision mismatch offers an explicit refresh and recovers both queries", async () => {
    let versionCalls = 0;
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/contracts/5/versions/11") {
        versionCalls += 1;
        return Promise.resolve(jsonResponse(200, { contentRevision: versionCalls === 1 ? 7 : 8 }));
      }
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage({ canRequest: false, currentContentRevision: 8, items: [{ ...ROUND, contentRevision: 8 }] })));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RevisionHarness />);
    expect(await screen.findByText("Review changed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Refresh document and reviews" }));
    expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(screen.queryByText("Review changed")).not.toBeInTheDocument();
    expect(versionCalls).toBe(2);
  });

  test("requests a review for the document revision on screen", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?") && !init?.method) return Promise.resolve(jsonResponse(200, reviewsPage({ items: [], total: 0, canRequest: true })));
      if (url === "/api/v1/contracts/5/versions/11/reviews" && init?.method === "POST") return Promise.resolve(jsonResponse(201, ROUND));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await user.click(await screen.findByRole("button", { name: "Request review" }));
    await waitFor(() => expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/reviews"))).toEqual({ expectedContentRevision: 7 }));
  });

  test("a requester can discuss but cannot decide", async () => {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage({ items: [{ ...ROUND, canDecide: false }] })));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    expect(await screen.findByRole("button", { name: "Add comment" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Request changes" })).not.toBeInTheDocument();
  });

  test.each([
    { status: "OUTDATED", isCurrentContent: false, closeReason: "CONTENT_CHANGED" },
    { status: "CLOSED", isCurrentContent: true, closeReason: "PUBLISHED" },
  ])("keeps $status discussion as read-only history", async (state) => {
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage({ items: [{ ...ROUND, ...state, closedAt: 1_700_000_200_000, canComment: false, canDecide: false }] })));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [ENTRY], page: 1, pageSize: 10, total: 1 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="ACTIVE" contentRevision={7} disabled={false} />);
    expect(await screen.findByText("Please document the failure response.")).toBeInTheDocument();
    expect(screen.queryByLabelText("Review comment")).not.toBeInTheDocument();
  });

  test("a conflict refreshes state without retrying the mutation", async () => {
    let listCalls = 0;
    let finishRefresh!: () => void;
    const refreshResponse = new Promise<Response>((resolve) => { finishRefresh = () => resolve(jsonResponse(200, reviewsPage())); });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?") && !init?.method) {
        listCalls += 1;
        return listCalls === 1 ? Promise.resolve(jsonResponse(200, reviewsPage())) : refreshResponse;
      }
      if (url.startsWith("/api/v1/version-reviews/41/entries?") && !init?.method) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      if (url === "/api/v1/version-reviews/41/entries" && init?.method === "POST") return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByText("Review changed")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add comment" })).not.toBeInTheDocument();
    expect(listCalls).toBe(1);
    await user.click(screen.getByRole("button", { name: "Refresh document and reviews" }));
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    finishRefresh();
    await waitFor(() => expect(listCalls).toBeGreaterThan(1));
    expect(await screen.findByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(mockFetch.mock.calls.filter(([url, init]) => url === "/api/v1/version-reviews/41/entries" && (init as RequestInit)?.method === "POST")).toHaveLength(1);
  });

  test("changing the displayed revision clears a typed decision comment", async () => {
    let revision = 7;
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage({ currentContentRevision: revision, items: [{ ...ROUND, contentRevision: revision }] })));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    const view = renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={revision} disabled={false} />);
    await user.type(await screen.findByLabelText("Review comment"), "Do not carry me");
    revision = 8;
    view.rerender(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={revision} disabled={false} />);
    expect(await screen.findByLabelText("Review comment")).toHaveValue("");
  });

  test("paginates rounds and entries using their independent server pages", async () => {
    mockFetch.mockImplementation((url: string) => {
      const parsed = new URL(url, "http://localhost");
      if (parsed.pathname.endsWith("/reviews")) {
        const page = Number(parsed.searchParams.get("page"));
        return Promise.resolve(jsonResponse(200, reviewsPage({ page, total: 6, items: [{ ...ROUND, id: page === 1 ? 41 : 42 }] })));
      }
      if (parsed.pathname.includes("/entries")) return Promise.resolve(jsonResponse(200, { items: [ENTRY], page: Number(parsed.searchParams.get("page")), pageSize: 10, total: 11 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await screen.findByText("Please document the failure response.");
    const pageTwos = screen.getAllByRole("button", { name: "2" });
    expect(screen.getAllByRole("button", { name: "Previous page" })).toHaveLength(2);
    expect(screen.getAllByRole("button", { name: "Next page" })).toHaveLength(2);
    await user.click(pageTwos[0]);
    await waitFor(() => expect(mockFetch.mock.calls.some(([url]) => String(url).includes("/entries?page=2"))).toBe(true));
    await user.click(pageTwos[1]);
    expect(await screen.findByText("Review #42")).toBeInTheDocument();
  });

  test("requesting from page two returns to and expands the newly created round", async () => {
    let created = false;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      const parsed = new URL(url, "http://localhost");
      if (parsed.pathname.endsWith("/reviews") && !init?.method) {
        const page = Number(parsed.searchParams.get("page"));
        const item = page === 2 ? { ...ROUND, id: 42 } : created ? { ...ROUND, id: 99 } : ROUND;
        return Promise.resolve(jsonResponse(200, reviewsPage({ page, total: 6, canRequest: page === 2, items: [item] })));
      }
      if (parsed.pathname === "/api/v1/contracts/5/versions/11/reviews" && init?.method === "POST") {
        created = true;
        return Promise.resolve(jsonResponse(201, { ...ROUND, id: 99 }));
      }
      if (parsed.pathname.includes("/entries")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await screen.findByText("Review #41");
    await user.click(screen.getByRole("button", { name: "2" }));
    await screen.findByText("Review #42");
    await user.click(screen.getByRole("button", { name: "Request review" }));
    const createdControl = await screen.findByRole("button", { name: /Review #99/ });
    expect(createdControl).toHaveAttribute("aria-expanded", "true");
  });

  test("renders an explicit refresh failure without enabling stale actions", async () => {
    let versionCalls = 0;
    mockFetch.mockImplementation((url: string) => {
      if (url === "/api/v1/contracts/5/versions/11") {
        versionCalls += 1;
        return Promise.resolve(versionCalls === 1 ? jsonResponse(200, { contentRevision: 7 }) : jsonResponse(500, { title: "Internal Server Error", status: 500 }));
      }
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage({ currentContentRevision: 8 })));
      if (url.startsWith("/api/v1/version-reviews/41/entries?")) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<RevisionHarness />);
    await user.click(await screen.findByRole("button", { name: "Refresh document and reviews" }));
    expect(await screen.findByText("Could not refresh the document and reviews")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
  });

  test("renders list and mutation failures inline", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    const failed = renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    expect(await screen.findByText("Could not load the reviews")).toBeInTheDocument();
    failed.unmount();
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/contracts/5/versions/11/reviews?")) return Promise.resolve(jsonResponse(200, reviewsPage()));
      if (url.startsWith("/api/v1/version-reviews/41/entries?") && !init?.method) return Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 10, total: 0 }));
      if (init?.method === "POST") return Promise.resolve(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<VersionReviews contractId={5} versionId={11} lifecycle="PROPOSED" contentRevision={7} disabled={false} />);
    await user.click(await screen.findByRole("button", { name: "Approve" }));
    expect(await screen.findByText("Could not update the review")).toBeInTheDocument();
  });
});
