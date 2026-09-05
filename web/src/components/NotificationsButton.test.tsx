import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import NotificationsButton from "./NotificationsButton";
import { findCall, signIn, type FetchMock } from "../test/contractsFixtures";

const ROW = { id: 41, recipientId: 1, timestamp: Date.now() - 60_000, type: "VERSION_CREATED", params: { contractName: "orders-api", actor: "Ada", version: "1.2.0" }, link: "/contracts/5/versions/12", wasSeen: false };
const SEEN = { ...ROW, id: 40, type: "VERSION_TRANSITIONED", params: { contractName: "orders-api", actor: "Ada", version: "1.1.0", from: "PROPOSED", to: "ACTIVE" }, wasSeen: true, link: null };
const UNKNOWN = { ...ROW, id: 39, type: "SOMETHING_NEW", params: {}, wasSeen: true };

/** Routes `wasSeen=false` to the badge envelope and everything else to the list. */
function serveNotifications(mockFetch: FetchMock, items: unknown[], unread: number, statuses: Record<string, number> = {}) {
  mockFetch.mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const key = `${method} ${url.split("?")[0]}`;
    if (statuses[key] != null) return Promise.resolve(new Response(null, { status: statuses[key] }));
    if (method !== "GET") return Promise.resolve(new Response(null, { status: 204 }));
    const body = url.includes("wasSeen=false") ? { items: items.slice(0, 1), page: 1, pageSize: 1, total: unread } : { items, page: 1, pageSize: 50, total: items.length };
    return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
  });
}

describe("NotificationsButton", () => {
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

  function render() {
    return renderWithProviders(
      <Routes>
        <Route path="*" element={<NotificationsButton />} />
        <Route path="/contracts/:id/versions/:vid" element={<h2>Version page</h2>} />
      </Routes>,
      { route: "/" },
    );
  }

  test("the badge shows the unread total; the drawer lists localized sentences newest first with a raw fallback", async () => {
    serveNotifications(mockFetch, [ROW, SEEN, UNKNOWN], 2);
    render();
    const bell = await screen.findByRole("button", { name: "Notifications (2 unread)" });
    expect(screen.getByText("2")).toBeInTheDocument();
    await userEvent.setup().click(bell);
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("Ada created version 1.2.0 of orders-api.")).toBeInTheDocument();
    expect(within(dialog).getByText("Ada moved orders-api 1.1.0 from Proposed to Active.")).toBeInTheDocument();
    expect(within(dialog).getByText("SOMETHING_NEW")).toBeInTheDocument();
    expect(within(dialog).getAllByRole("listitem")).toHaveLength(3);
    expect(within(dialog).getByRole("button", { name: "Mark all as seen" })).toBeInTheDocument();
  });

  test("Open marks the row seen and navigates; seen/unseen and delete post to their endpoints", async () => {
    serveNotifications(mockFetch, [ROW, SEEN], 1);
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Mark notification 40 as unseen" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/notifications/40/unseen")).toBeDefined());
    await user.click(within(dialog).getByRole("button", { name: "Delete notification 40" }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/notifications/40")).toBeDefined());
    expect(within(dialog).queryByRole("button", { name: "Open the subject of notification 40" })).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Open the subject of notification 41" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/notifications/41/seen")).toBeDefined());
    expect(await screen.findByRole("heading", { name: "Version page" })).toBeInTheDocument();
  });

  test("Mark all as seen posts seen-all; an empty list and a failed load each say so", async () => {
    serveNotifications(mockFetch, [ROW], 1);
    const user = userEvent.setup();
    render();
    await user.click(await screen.findByRole("button", { name: /^Notifications/ }));
    await user.click(await screen.findByRole("button", { name: "Mark all as seen" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/notifications/seen-all")).toBeDefined());
    serveNotifications(mockFetch, [], 0);
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.click(screen.getByRole("button", { name: /^Notifications/ }));
    expect(await screen.findByText(/No notifications yet/)).toBeInTheDocument();
  });
});
