import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { Route, Routes, useLocation } from "react-router-dom";
import ToadieLinkResolver from "./ToadieLinkResolver";
import type { ToadieLinkMatch } from "../api/toadie";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const TOKEN_KEY = "covenant.auth.token";

type FetchMock = ReturnType<typeof vi.fn>;

const MATCH: ToadieLinkMatch = {
  linkId: 7,
  contractId: 42,
  contractName: "Orders API",
  type: "OPENAPI",
  system: { id: 3, name: "Commerce" },
  latestVersion: { id: 9, version: "1.2.0", lifecycle: "ACTIVE", checkErrors: 0, checkWarnings: 0, checkComplete: true },
  connection: { id: 1, name: "Main Toadie", browserUrl: "http://toadie.internal" },
  blueprint: "api",
  identifier: "orders",
  title: "Orders",
};

function page(items: ToadieLinkMatch[], total = items.length) {
  return { items, page: 1, pageSize: 100, total };
}

/** Echoes the current location so the single-match redirect is observable. */
function LocationProbe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname}</div>;
}

function renderPage(route: string) {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/toadie/:blueprint/:identifier" element={<ToadieLinkResolver />} />
        <Route path="/contracts/:id" element={<div>contract page</div>} />
      </Routes>
      <LocationProbe />
    </>,
    { route },
  );
}

describe("ToadieLinkResolver page", () => {
  let mockFetch: FetchMock;

  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    localStorage.setItem(TOKEN_KEY, "fake-token");
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  const requestedUrl = () => String(mockFetch.mock.calls.at(-1)?.[0]);

  test("a single match redirects to the contract, replacing this page in the history", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, page([MATCH])));
    renderPage("/toadie/api/orders");

    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/contracts/42"));
    expect(screen.getByText("contract page")).toBeInTheDocument();
  });

  test("several matches list themselves with the connection that produced each", async () => {
    const second = { ...MATCH, linkId: 8, contractId: 43, contractName: "Orders events", type: "ASYNCAPI" as const, connection: { id: 2, name: "Spare Toadie", browserUrl: "http://spare.internal" } };
    mockFetch.mockResolvedValue(jsonResponse(200, page([MATCH, second])));
    renderPage("/toadie/api/orders");

    expect(await screen.findByRole("link", { name: "Open contract Orders API" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open contract Orders events" })).toBeInTheDocument();
    expect(screen.getByText("2 contracts cover this entity. Pick the one you need.")).toBeInTheDocument();
    expect(screen.getByText("Main Toadie")).toBeInTheDocument();
    expect(screen.getByText("Spare Toadie")).toBeInTheDocument();
    // No redirect happened — the resolver stays put so the user can choose.
    expect(screen.getByTestId("location")).toHaveTextContent("/toadie/api/orders");
  });

  test("no match is a plain empty state naming the entity, not an error", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, page([])));
    renderPage("/toadie/dataset/settlements");

    expect(await screen.findByText(/No Covenant contract is linked to Toadie dataset/)).toBeInTheDocument();
    // The identifier shows twice: the page header's <Code> and the sentence itself.
    expect(screen.getAllByText(/settlements/).length).toBeGreaterThanOrEqual(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  test("a failed lookup renders the shared load error, never a blank page", async () => {
    mockFetch.mockResolvedValue(jsonResponse(500, { title: "Internal Server Error", status: 500 }));
    renderPage("/toadie/api/orders");

    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
  });

  test("a match without a version says so instead of linking nowhere", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, page([{ ...MATCH, latestVersion: null }, { ...MATCH, linkId: 8, contractId: 43 }])));
    renderPage("/toadie/api/orders");

    expect(await screen.findByText("No version yet")).toBeInTheDocument();
  });

  // The identifier reaches the server as a query VALUE, so every character a Port identifier may
  // carry has to survive both React Router's path decoding and URLSearchParams encoding. An
  // encoded `/` must stay part of the identifier, and `+` must never arrive as a space.
  test.each([
    ["an encoded slash", "/toadie/api/team%2Forders", "team/orders"],
    ["a plus sign", "/toadie/api/orders%2Bv2", "orders+v2"],
    ["a colon and an at sign", "/toadie/api/orders%3Aeu%40prod", "orders:eu@prod"],
    ["an apostrophe", "/toadie/api/it%27s", "it's"],
    ["a unicode letter", "/toadie/api/%C5%82abedz", "łabedz"],
    ["dots and dashes", "/toadie/api/orders.v2_x-y", "orders.v2_x-y"],
  ])("%s survives the round trip into the lookup", async (_name, route, expected) => {
    mockFetch.mockResolvedValue(jsonResponse(200, page([])));
    renderPage(route);

    await waitFor(() => expect(mockFetch).toHaveBeenCalled());
    const params = new URLSearchParams(requestedUrl().split("?")[1]);
    expect(params.get("identifier")).toBe(expected);
    expect(params.get("blueprint")).toBe("api");
    // The identifier is never spliced into the path — that is what breaks on `%2F`.
    expect(requestedUrl().split("?")[0]).toBe("/api/v1/toadie-links");
  });

  test("the blueprint travels as sent, so a dataset lookup cannot answer as an api one", async () => {
    mockFetch.mockResolvedValue(jsonResponse(200, page([])));
    renderPage("/toadie/dataset/orders");

    await waitFor(() => expect(mockFetch).toHaveBeenCalled());
    expect(new URLSearchParams(requestedUrl().split("?")[1]).get("blueprint")).toBe("dataset");
  });
});
