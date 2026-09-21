import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { Route, Routes } from "react-router-dom";
import NewVersion from "./NewVersion";
import { bodyOf, calledUrl, CLEAN_REPORT, CONTENT, CONTRACT, findCall, serve, signIn, SOFT_ERROR, VERSION, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const editorValue = () => (screen.getByRole("textbox", { name: "Contract document" }) as HTMLTextAreaElement).value;
const BREAKING_FACT = { severity: "WARN" as const, source: "BREAKING" as const, code: "REMOVED_OPERATION", message: "GET /orders was removed", path: "/paths/~1orders/get" };
const BREAKING_ERROR = { severity: "ERROR" as const, source: "BREAKING" as const, code: "BREAKING_WITHOUT_MAJOR_BUMP", message: "Breaking changes require a major version bump" };

function renderPage(route = "/contracts/5/versions/new", state?: unknown) {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/versions/new" element={<NewVersion />} />
      <Route path="/contracts/:id/versions/:vid" element={<h2>Version page</h2>} />
      <Route path="/contracts/:id" element={<h2>Contract page</h2>} />
    </Routes>,
    { route, state },
  );
}

describe("NewVersion page", () => {
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
    "POST /api/v1/contracts/versions/check": { status: 200, body: CLEAN_REPORT },
  };

  test("an inferred document uses the selected catalog version from its handoff", async () => {
    serve(mockFetch, base);
    renderPage("/contracts/5/versions/new", { content: CONTENT, sourceUrl: null, version: "9.0.0" });
    expect(await screen.findByLabelText("Version")).toHaveValue("9.0.0");
    expect(await screen.findByRole("textbox", { name: "Contract document" })).toHaveValue(CONTENT);
  });

  test("defaults to the selected line's next patch, allows a chronological backport, validates SemVer, and copies ?from=", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    renderPage("/contracts/5/versions/new?from=11");
    expect(await screen.findByRole("heading", { level: 2, name: "New version of orders-api" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Version")).toHaveValue("1.1.1"));
    expect(screen.getByText(/selected release line currently ends at 1.1.0/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Contract document" })).toHaveValue(CONTENT));
    await user.click(screen.getByRole("button", { name: "Major" }));
    expect(screen.getByLabelText("Version")).toHaveValue("2.0.0");
    await user.clear(screen.getByLabelText("Version"));
    await user.type(screen.getByLabelText("Version"), "1.0.5");
    expect(screen.queryByText(/must be greater/)).not.toBeInTheDocument();
    await user.clear(screen.getByLabelText("Version"));
    await user.type(screen.getByLabelText("Version"), "v2");
    expect(screen.getByText("Use strict SemVer, e.g. 1.2.0 or 2.0.0-rc.1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
    await waitFor(() => expect(calledUrl(mockFetch, "POST", (u) => u === "/api/v1/contracts/versions/check")).toBeDefined());
    // The contract is named so the server compares against its published predecessor for breaking changes.
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/versions/check"))).toMatchObject({ contractId: 5 });
  });

  test("fetches an explicitly selected old-line source and every major-filtered page without losing the source document", async () => {
    const backportContent = CONTENT.replace("version: 1.1.0", "version: 1.9.1");
    const backport = { ...VERSION, id: 200, version: "1.9.1", content: backportContent };
    serve(mockFetch, {
      ...base,
      "GET /api/v1/contracts/5/versions/200": { status: 200, body: backport },
      "GET /api/v1/contracts/5/versions?": (url) => {
        const page = new URL(url, "http://covenant.local").searchParams.get("page");
        return page === "1"
          ? { status: 200, body: { ...VERSION_PAGE, items: Array.from({ length: 100 }, (_, index) => ({ ...VERSION_PAGE.items[0], id: 300 + index, version: `1.10.${100 - index}` })), page: 1, pageSize: 100, total: 101 } }
          : { status: 200, body: { ...VERSION_PAGE, items: [backport], page: 2, pageSize: 100, total: 101 } };
      },
    });
    renderPage("/contracts/5/versions/new?from=200&major=1");
    await waitFor(() => expect(screen.getByLabelText("Version")).toHaveValue("1.9.2"));
    expect(screen.getByRole("textbox", { name: "Contract document" })).toHaveValue(backportContent);
    expect(calledUrl(mockFetch, "GET", (url) => url.includes("page=2") && url.includes("major=1"))).toBeDefined();
  });

  test("a blank start renders the type's template; the strict save posts and lands on the version", async () => {
    serve(mockFetch, { ...base, "POST /api/v1/contracts/5/versions": { status: 201, body: { ...VERSION, id: 12, version: "1.1.1" } } });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(editorValue()).toContain("openapi: 3.1.0"));
    expect(editorValue()).toContain("version: 1.1.1");
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions")).toBeDefined());
    const body = bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions")) as { version: string; content: string };
    expect(body.version).toBe("1.1.1");
    expect(body.content).toContain("openapi: 3.1.0");
    expect(await screen.findByRole("heading", { level: 2, name: "Version page" })).toBeInTheDocument();
  });

  test("an explicitly selected empty line starts at that major and a missing source is shown inline", async () => {
    serve(mockFetch, {
      ...base,
      "GET /api/v1/contracts/5/versions?": { status: 200, body: { ...VERSION_PAGE, items: [], total: 0 } },
    });
    const first = renderPage("/contracts/5/versions/new?major=2");
    await waitFor(() => expect(screen.getByLabelText("Version")).toHaveValue("2.0.0"));
    expect(editorValue()).toContain("version: 2.0.0");
    first.unmount();

    serve(mockFetch, {
      ...base,
      "GET /api/v1/contracts/5/versions/999": { status: 404, body: { title: "Not Found", status: 404 } },
    });
    renderPage("/contracts/5/versions/new?from=999");
    expect(await screen.findByText("Could not load the selected starting version")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
  });

  test("a breaking-only strict rejection keeps its contract baseline and retries with the waiver", async () => {
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/versions/check": (_url, init) => {
        const request = JSON.parse(String(init?.body)) as { contractId?: number };
        return request.contractId === 5
          ? { status: 200, body: { ...CLEAN_REPORT, baselineVersion: "1.0.0", findings: [BREAKING_FACT, BREAKING_ERROR], errors: 1, warnings: 1 } }
          : { status: 200, body: CLEAN_REPORT };
      },
      "POST /api/v1/contracts/5/versions": { status: 400, body: { title: "Bad Request", status: 400, detail: "The document has 1 blocking finding(s): BREAKING_WITHOUT_MAJOR_BUMP" } },
      "POST /api/v1/contracts/5/versions?allowInvalid=true": { status: 201, body: { ...VERSION, id: 12 } },
    });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(editorValue()).toContain("openapi"));
    expect(await screen.findByText("GET /orders was removed")).toBeInTheDocument();
    expect(await screen.findByText("Breaking changes require a major version bump")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText(/found 1 blocking finding/)).toBeInTheDocument();
    expect(within(screen.getByRole("dialog")).getByText("BREAKING_WITHOUT_MAJOR_BUMP")).toBeInTheDocument();
    const checkBodies = mockFetch.mock.calls
      .filter(([url, init]) => url === "/api/v1/contracts/versions/check" && (init as RequestInit | undefined)?.method === "POST")
      .map((call) => bodyOf(call));
    expect(checkBodies.length).toBeGreaterThanOrEqual(2);
    expect(checkBodies).toEqual(expect.arrayContaining([expect.objectContaining({ contractId: 5 })]));
    expect(checkBodies.every((body) => (body as { contractId?: number }).contractId === 5)).toBe(true);
    await user.click(screen.getByRole("button", { name: "Save anyway" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions?allowInvalid=true")).toBeDefined());
  });

  test("a syntax finding disables the save with the blocking hint", async () => {
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/versions/check": { status: 200, body: { ...CLEAN_REPORT, findings: [{ ...SOFT_ERROR, source: "SYNTAX", code: "YAML_PARSE", message: "bad indent" }], errors: 1 } },
    });
    renderPage();
    expect(await screen.findByText("bad indent")).toBeInTheDocument();
    expect(screen.getByText(/does not parse/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
  });

  test("a non-writer is turned away; another 400 renders the vocabulary plus the server's detail", async () => {
    serve(mockFetch, { ...base, "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, canWrite: false } } });
    const { unmount } = renderPage();
    expect(await screen.findByText(/You cannot edit this contract/)).toBeInTheDocument();
    unmount();
    serve(mockFetch, { ...base, "POST /api/v1/contracts/5/versions": { status: 400, body: { title: "Bad Request", status: 400, detail: "Version 1.1.1 must be greater than the highest existing version 2.0.0" } } });
    renderPage();
    await waitFor(() => expect(editorValue()).toContain("openapi"));
    await userEvent.setup().click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("The version was rejected — see the details")).toBeInTheDocument();
    expect(screen.getByText(/must be greater than the highest existing version 2.0.0/)).toBeInTheDocument();
  });
});
