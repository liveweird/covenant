import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import ContractHistory from "./ContractHistory";
import { EVENT, serve, signIn, type FetchMock } from "../test/contractsFixtures";

const at = (id: number, type: string, params: Record<string, string>) => ({ ...EVENT, id, type, params });

describe("ContractHistory", () => {
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

  test("every event kind reads as a sentence in the viewer's language; owners and names get a detail line", async () => {
    const items = [
      at(1, "CREATED", { name: "orders-api", type: "OPENAPI" }),
      at(2, "UPDATED", { name: "orders-api-v2" }),
      at(3, "OWNER_CHANGED", { "owner.from": "TEAM:3", "owner.to": "USER:8" }),
      at(4, "VERSION_CREATED", { version: "1.0.0" }),
      at(5, "VERSION_CONTENT_UPDATED", { version: "1.0.0" }),
      at(6, "VERSION_TRANSITIONED", { version: "1.0.0", from: "DRAFT", to: "PROPOSED" }),
      at(7, "VERSION_RECHECKED", { version: "1.0.0" }),
      at(8, "VERSION_DELETED", { version: "1.1.0" }),
      at(9, "IMPORTED", { version: "1.2.0" }),
      at(10, "SOMETHING_NEW", { version: "9" }),
      at(11, "VERSION_SOURCE_CHANGED", { version: "1.2.0", sourceUrl: "https://github.com/acme/c/blob/main/o.yaml" }),
      at(12, "VERSION_SOURCE_CHANGED", { version: "1.2.0", sourceUrl: "" }),
      at(13, "VERSION_SYNCED", { version: "1.2.0" }),
    ];
    serve(mockFetch, { "GET /api/v1/contracts/5/events?": { status: 200, body: { items, page: 1, pageSize: 20, total: 13 } } });
    renderWithProviders(<ContractHistory contractId={5} />);
    const region = await screen.findByRole("region", { name: "History" });
    // The region renders while loading; wait for the first entry before the synchronous sweep.
    expect(await within(region).findByText("Contract created (OpenAPI)")).toBeInTheDocument();
    for (const text of [
      "Details updated",
      "Name: orders-api-v2",
      "Owner changed",
      "From team #3 to person #8",
      "Version 1.0.0 created",
      "Version 1.0.0: document updated",
      "Version 1.0.0: Draft → Proposed",
      "Version 1.0.0 re-checked",
      "Version 1.1.0 deleted",
      "Version 1.2.0 imported",
      "SOMETHING_NEW",
      "Version 1.2.0: source linked",
      "Source: https://github.com/acme/c/blob/main/o.yaml",
      "Version 1.2.0: source unlinked",
      "Version 1.2.0 synced from its source",
    ]) {
      expect(within(region).getByText(text)).toBeInTheDocument();
    }
    expect(within(region).getAllByText(/Ada Admin ·/)).toHaveLength(13);
    expect(screen.queryByRole("navigation")).not.toBeInTheDocument();
  });

  test("pages through a long history, newest first as the server sends it", async () => {
    const page1 = { items: [at(2, "VERSION_CREATED", { version: "2.0.0" })], page: 1, pageSize: 10, total: 11 };
    const page2 = { items: [at(1, "VERSION_CREATED", { version: "1.0.0" })], page: 2, pageSize: 10, total: 11 };
    mockFetch.mockImplementation((input: RequestInfo | URL) => {
      const url = String(input);
      const body = url.includes("page=2") ? page2 : page1;
      return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
    });
    renderWithProviders(<ContractHistory contractId={5} />);
    expect(await screen.findByText("Version 2.0.0 created")).toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "2" }));
    expect(await screen.findByText("Version 1.0.0 created")).toBeInTheDocument();
  });

  test("an empty history says so; a failed load is an error, never an empty history", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5/events?": { status: 200, body: { items: [], page: 1, pageSize: 10, total: 0 } } });
    const { unmount } = renderWithProviders(<ContractHistory contractId={5} />);
    expect(await screen.findByText("No history yet.")).toBeInTheDocument();
    unmount();
    serve(mockFetch, { "GET /api/v1/contracts/5/events?": { status: 500, body: {} } });
    renderWithProviders(<ContractHistory contractId={5} />);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.queryByText("No history yet.")).not.toBeInTheDocument();
  });
});
