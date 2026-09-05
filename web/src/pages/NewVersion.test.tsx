import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { Route, Routes } from "react-router-dom";
import NewVersion from "./NewVersion";
import { bodyOf, calledUrl, CLEAN_REPORT, CONTENT, CONTRACT, findCall, serve, signIn, SOFT_ERROR, VERSION, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const editorValue = () => (screen.getByRole("textbox", { name: "Contract document" }) as HTMLTextAreaElement).value;

function renderPage(route = "/contracts/5/versions/new") {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/versions/new" element={<NewVersion />} />
      <Route path="/contracts/:id/versions/:vid" element={<h2>Version page</h2>} />
      <Route path="/contracts/:id" element={<h2>Contract page</h2>} />
    </Routes>,
    { route },
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

  test("defaults to the next patch, bumps off the highest, validates the number, and copies ?from=", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    renderPage("/contracts/5/versions/new?from=11");
    expect(await screen.findByRole("heading", { level: 2, name: "New version of orders-api" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Version")).toHaveValue("1.1.1"));
    expect(screen.getByText(/highest version so far is 1.1.0/)).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Contract document" })).toHaveValue(CONTENT));
    await user.click(screen.getByRole("button", { name: "Major" }));
    expect(screen.getByLabelText("Version")).toHaveValue("2.0.0");
    await user.clear(screen.getByLabelText("Version"));
    await user.type(screen.getByLabelText("Version"), "1.0.5");
    expect(screen.getByText("The version must be greater than 1.1.0")).toBeInTheDocument();
    await user.clear(screen.getByLabelText("Version"));
    await user.type(screen.getByLabelText("Version"), "v2");
    expect(screen.getByText("Use strict SemVer, e.g. 1.2.0 or 2.0.0-rc.1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
    await waitFor(() => expect(calledUrl(mockFetch, "POST", (u) => u === "/api/v1/contracts/versions/check")).toBeDefined());
    // The contract is named so the server compares against its ACTIVE version for breaking changes.
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/versions/check"))).toMatchObject({ contractId: 5 });
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

  test("a soft rejection opens Save-anyway and retries with the waiver; a HARD finding blocks the button", async () => {
    let posts = 0;
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/versions/check": { status: 200, body: { ...CLEAN_REPORT, findings: [SOFT_ERROR], errors: 1 } },
      "POST /api/v1/contracts/5/versions": () => {
        posts += 1;
        return posts === 1 ? { status: 400, body: { title: "Bad Request", status: 400, detail: "The document has 1 blocking finding(s): OAS_PARSE: paths is required" } } : { status: 201, body: VERSION };
      },
      "POST /api/v1/contracts/5/versions?allowInvalid=true": { status: 201, body: { ...VERSION, id: 12 } },
    });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(editorValue()).toContain("openapi"));
    expect(await screen.findByText("paths is required")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText(/found 1 blocking finding/)).toBeInTheDocument();
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
