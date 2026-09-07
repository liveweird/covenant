import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { Route, Routes } from "react-router-dom";
import VersionDiff from "./VersionDiff";
import { COMPATIBILITY, COMPATIBILITY_NO_BASELINE, CONTRACT, findCall, OLD_VERSION, serve, signIn, VERSION, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

function renderPage(route = "/contracts/5/diff") {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/diff" element={<VersionDiff />} />
    </Routes>,
    { route },
  );
}

describe("VersionDiff page", () => {
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

  const base = {
    "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
    "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
    "GET /api/v1/contracts/5/versions/11": { status: 200, body: VERSION },
    "GET /api/v1/contracts/5/versions/10": { status: 200, body: OLD_VERSION },
    "GET /api/v1/contracts/5/versions/11/compatibility?against=10": { status: 200, body: COMPATIBILITY },
    "GET /api/v1/contracts/5/versions/10/compatibility?against=10": { status: 200, body: COMPATIBILITY_NO_BASELINE },
  };

  test("defaults to the two highest versions and renders the line diff with the −/+ rows and stats", async () => {
    serve(mockFetch, base);
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Compare versions of orders-api" })).toBeInTheDocument();
    await waitFor(() => expect(findCall(mockFetch, "GET", "/api/v1/contracts/5/versions/10")).toBeDefined());
    expect(await screen.findByText("- version: 1.0.0")).toBeInTheDocument();
    expect(screen.getByText("+ version: 1.1.0")).toBeInTheDocument();
    expect(screen.getByText("+1 / −1 lines")).toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Differences from 1.0.0 to 1.1.0" })).toBeInTheDocument();
    expect(screen.getByLabelText("From", { selector: "input" })).toHaveValue("1.0.0 (Active)");
    expect(screen.getByLabelText("To", { selector: "input" })).toHaveValue("1.1.0 (Draft)");
  });

  test("?from=&to= pick the sides and identical documents say so; the hide-unchanged switch is persisted state", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5/versions/10": { status: 200, body: { ...OLD_VERSION, content: VERSION.content } } });
    const user = userEvent.setup();
    renderPage("/contracts/5/diff?from=10&to=11");
    expect(await screen.findByText("The two documents are identical.")).toBeInTheDocument();
    expect(screen.getByLabelText("From", { selector: "input" })).toHaveValue("1.0.0 (Active)");
    expect(screen.getByLabelText("To", { selector: "input" })).toHaveValue("1.1.0 (Draft)");
    expect(screen.getByText("+0 / −0 lines")).toBeInTheDocument();
    const toggle = screen.getByRole("switch", { name: "Hide unchanged lines" });
    expect(toggle).toBeChecked();
    await user.click(toggle);
    expect(toggle).not.toBeChecked();
    expect(localStorage.getItem("covenant.viewSettings.versionDiff.hideUnchanged")).toBe("false");
  });

  test("picking another side rewrites the URL and loads that version", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    renderPage("/contracts/5/diff?from=10&to=11");
    await screen.findByText("- version: 1.0.0");
    await user.click(screen.getByLabelText("To", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "1.0.0 (Active)" }));
    expect(await screen.findByText("The two documents are identical.")).toBeInTheDocument();
  });

  test("a single-version contract explains that two are needed", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5/versions?": { status: 200, body: { ...VERSION_PAGE, items: [VERSION_PAGE.items[0]], total: 1 } } });
    renderPage();
    expect(await screen.findByText("Two versions are needed for a comparison.")).toBeInTheDocument();
  });

  test("shows the compatibility card for the picked pair", async () => {
    serve(mockFetch, base);
    renderPage();
    const card = await screen.findByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Backward compatible")).toBeInTheDocument();
    expect(within(card).getByText(/is backward compatible with/)).toBeInTheDocument();
    expect(within(card).getByText(/minor bump/)).toBeInTheDocument();
  });

  test("a compatibility load failure renders inline; the diff still shows", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5/versions/11/compatibility?against=10": { status: 500, body: { title: "Internal Server Error", status: 500 } } });
    renderPage();
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(await screen.findByText("- version: 1.0.0")).toBeInTheDocument();
  });
});
