import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { Route, Routes } from "react-router-dom";
import VersionPage from "./VersionPage";
import { bodyOf, CLEAN_REPORT, CONTENT, CONTRACT, findCall, OLD_VERSION, serve, signIn, VERSION, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

function renderPage(route = "/contracts/5/versions/11") {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/versions/:vid" element={<VersionPage />} />
      <Route path="/contracts/:id" element={<h2>Contract page</h2>} />
    </Routes>,
    { route },
  );
}

describe("VersionPage", () => {
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
    "GET /api/v1/contracts/5/versions/11": { status: 200, body: VERSION },
    "GET /api/v1/contracts/5/versions/10": { status: 200, body: OLD_VERSION },
    "POST /api/v1/contracts/versions/check": { status: 200, body: CLEAN_REPORT },
  };

  test("shows the document read-only with its stored findings, meta and the writer's lifecycle moves", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "orders-api 1.1.0" })).toBeInTheDocument();
    const editor = screen.getByRole("textbox", { name: "Contract document" });
    expect(editor).toHaveValue(CONTENT);
    expect(editor).toHaveAttribute("readonly");
    expect(screen.getByText("info-contact")).toBeInTheDocument();
    expect(screen.getByText("Format: yaml")).toBeInTheDocument();
    expect(screen.getByText("aaaaaaaaaaaa")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Propose" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Edit document" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Go to line 2, column 1" }));
    expect(screen.getByTestId("code-editor-stub")).toHaveAttribute("data-jump", "2:1");
    expect(screen.getByTestId("code-editor-stub")).toHaveAttribute("data-diagnostics", "1");
  });

  test("a reader sees no moves, no Edit, and a More menu with Download and Compare only", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, canWrite: false } } });
    const user = userEvent.setup();
    renderPage("/contracts/5/versions/10");
    await screen.findByRole("heading", { level: 2, name: "orders-api 1.0.0" });
    expect(screen.queryByRole("button", { name: "Deprecate" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit document" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "More actions" }));
    expect(await screen.findByRole("menuitem", { name: "Download" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Compare with latest" })).toHaveAttribute("href", "/contracts/5/diff?from=10&to=11");
    expect(screen.queryByRole("menuitem", { name: "Re-run checks" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Delete" })).not.toBeInTheDocument();
  });

  test("a transition posts and toasts; a 409 renders the state vocabulary", async () => {
    serve(mockFetch, { ...base, "POST /api/v1/contracts/5/versions/11/transition": { status: 200, body: { ...VERSION, lifecycle: "PROPOSED" } } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Propose" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/transition")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/transition"))).toEqual({ to: "PROPOSED" });
    serve(mockFetch, { ...base, "POST /api/v1/contracts/5/versions/11/transition": { status: 409, body: { title: "Conflict", status: 409 } } });
    await user.click(screen.getByRole("button", { name: "Propose" }));
    expect(await screen.findByText(/That transition is not allowed/)).toBeInTheDocument();
  });

  test("editing runs the live check, saves the content, and locks the other actions meanwhile", async () => {
    serve(mockFetch, { ...base, "PUT /api/v1/contracts/5/versions/11/content": { status: 200, body: VERSION } });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Edit document" }));
    expect(screen.queryByRole("button", { name: "Propose" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "More actions" })).toBeDisabled();
    expect(screen.getByRole("button", { name: /^save$/i })).toBeDisabled();
    expect(screen.getByText("No changes yet")).toBeInTheDocument();
    const editor = screen.getByRole("textbox", { name: "Contract document" });
    await user.type(editor, "# note");
    expect(screen.getByText("Unsaved changes")).toBeInTheDocument();
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/versions/check")).toBeDefined());
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/content")).toBeDefined());
    expect((bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/content")) as { content: string }).content).toContain("# note");
    await waitFor(() => expect(screen.getByRole("button", { name: "Edit document" })).toBeInTheDocument());
  });

  test("cancel leaves editing without saving; a published version offers no Edit", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    const { unmount } = renderPage();
    await user.click(await screen.findByRole("button", { name: "Edit document" }));
    await user.type(screen.getByRole("textbox", { name: "Contract document" }), "x");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.getByRole("textbox", { name: "Contract document" })).toHaveValue(CONTENT);
    expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/content")).toBeUndefined();
    unmount();
    serve(mockFetch, base);
    renderPage("/contracts/5/versions/10");
    await screen.findByRole("heading", { level: 2, name: "orders-api 1.0.0" });
    expect(screen.queryByRole("button", { name: "Edit document" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Deprecate" })).toBeInTheDocument();
  });

  test("More: re-run checks posts; delete confirms, deletes and returns to the contract", async () => {
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/5/versions/11/recheck": { status: 200, body: VERSION },
      "DELETE /api/v1/contracts/5/versions/11": { status: 204 },
    });
    const user = userEvent.setup();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "More actions" }));
    await user.click(await screen.findByRole("menuitem", { name: "Re-run checks" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/recheck")).toBeDefined());
    await user.click(screen.getByRole("button", { name: "More actions" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: /^delete$/i }));
    await waitFor(() => expect(findCall(mockFetch, "DELETE", "/api/v1/contracts/5/versions/11")).toBeDefined());
    expect(await screen.findByRole("heading", { level: 2, name: "Contract page" })).toBeInTheDocument();
  });

  test("a missing version says so", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5/versions/11": { status: 404, body: { title: "Not Found", status: 404 } } });
    renderPage();
    expect(await screen.findByText("This version does not exist (or was deleted).")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the contract" })).toHaveAttribute("href", "/contracts/5");
  });
});
