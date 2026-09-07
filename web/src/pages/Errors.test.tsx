import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import Errors from "./Errors";
import { calledUrl, ERROR_FACETS, ERROR_PAGE, serve, signIn, type FetchMock } from "../test/contractsFixtures";

describe("Errors page", () => {
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

  function serveErrors(overrides: Record<string, { status: number; body?: unknown }> = {}) {
    serve(mockFetch, {
      "GET /api/v1/contracts/errors?": { status: 200, body: ERROR_PAGE },
      "GET /api/v1/contracts/errors/facets?": { status: 200, body: ERROR_FACETS },
      ...overrides,
    });
  }

  test("rows are grouped by contract: one contract link, two version links", async () => {
    serveErrors();
    renderWithProviders(<Errors />);
    expect(await screen.findAllByRole("link", { name: "Open contract orders-api" })).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Open version 1.1.0 of orders-api" })).toHaveAttribute("href", "/contracts/5/versions/11");
    expect(screen.getByRole("link", { name: "Open version 1.0.0 of orders-api" })).toHaveAttribute("href", "/contracts/5/versions/10");
  });

  test("repeated findings collapse into one badge with a ×2 suffix and the first message as its title", async () => {
    serveErrors();
    renderWithProviders(<Errors />);
    const badge = await screen.findByTitle("paths is required");
    expect(badge).toHaveTextContent("OAS_PARSE ×2");
  });

  test("the default request carries the severity/lifecycle/source defaults", async () => {
    serveErrors();
    renderWithProviders(<Errors />);
    await screen.findByText("OAS_PARSE ×2");
    const call = calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors?"));
    expect(call).toBeDefined();
    const url = new URL(String(call![0]), "http://localhost");
    expect(url.searchParams.getAll("severity")).toEqual(["ERROR", "WARN"]);
    expect(url.searchParams.getAll("lifecycle")).toHaveLength(4);
    expect(url.searchParams.getAll("source")).toHaveLength(5);
  });

  test("the facet tiles show the totals", async () => {
    serveErrors();
    renderWithProviders(<Errors />);
    await waitFor(() => expect(document.querySelector('[data-tile="Contracts"]')?.textContent).toContain("1"));
    expect(document.querySelector('[data-tile="Versions"]')?.textContent).toContain("2");
    expect(document.querySelector('[data-tile="Findings"]')?.textContent).toContain("4");
  });

  test("toggling the Error chip drops severity=ERROR from the request", async () => {
    serveErrors();
    const user = userEvent.setup();
    renderWithProviders(<Errors />);
    await screen.findByText("OAS_PARSE ×2");
    await user.click(screen.getByRole("checkbox", { name: "Error" }));
    await waitFor(() => {
      const call = calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors?") && !u.includes("severity=ERROR"));
      expect(call).toBeDefined();
    });
  });

  test("toggling the Lint chip drops source=LINT from the request, and no source at all fires none", async () => {
    serveErrors();
    const user = userEvent.setup();
    renderWithProviders(<Errors />);
    await screen.findByText("OAS_PARSE ×2");
    const sourceGroup = within(screen.getByRole("group", { name: "Source" }));
    await user.click(sourceGroup.getByRole("checkbox", { name: "Lint" }));
    await waitFor(() => {
      const call = calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors?") && !u.includes("source=LINT") && u.includes("source=SCHEMA"));
      expect(call).toBeDefined();
    });
    for (const name of ["Schema", "Semantic", "Breaking change", "System"]) await user.click(sourceGroup.getByRole("checkbox", { name }));
    expect(await screen.findByText("Pick at least one severity and one source to show rows")).toBeInTheDocument();
    // Each intermediate click legitimately fires a narrower request; the one shape that must
    // never leave is the match-nothing request — an errors/facets URL with NO source at all
    // (the server reads an empty list as "any").
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors") && !u.includes("source="))).toBeUndefined();
  });

  test("turning off every severity chip shows the no-selection state and fires no request", async () => {
    serveErrors();
    const user = userEvent.setup();
    renderWithProviders(<Errors />);
    await screen.findByText("OAS_PARSE ×2");
    await user.click(screen.getByRole("checkbox", { name: "Error" }));
    // One severity still on ("Warning") — the request still fires; wait for it to settle before
    // clearing the mock, so the assertion below only sees calls made AFTER going to noSelection.
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors?") && !u.includes("severity=ERROR"))).toBeDefined());
    mockFetch.mockClear();
    await user.click(screen.getByRole("checkbox", { name: "Warning" }));
    expect(await screen.findByText("Pick at least one severity and one source to show rows")).toBeInTheDocument();
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/contracts/errors?"))).toBeUndefined();
  });

  test("shows an alert when the report fails to load", async () => {
    serveErrors({ "GET /api/v1/contracts/errors?": { status: 500, body: { title: "boom", status: 500 } } });
    renderWithProviders(<Errors />);
    expect(await screen.findByText("Could not load the errors report")).toBeInTheDocument();
  });
});
