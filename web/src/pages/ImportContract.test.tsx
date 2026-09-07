import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import ImportContract from "./ImportContract";
import { bodyOf, CLEAN_REPORT, CONTENT, findCall, serve, signIn, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

describe("ImportContract page", () => {
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

  const base = { "POST /api/v1/contracts/versions/check": { status: 200, body: CLEAN_REPORT } };

  async function pasteAndPickSystem() {
    const editor = await screen.findByRole("textbox", { name: "Contract document" });
    fireEvent.change(editor, { target: { value: CONTENT } });
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue("Orders"));
    expect(screen.getByLabelText("Version")).toHaveValue("1.1.0");
    fireEvent.click(screen.getByLabelText("System", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "gateway" }));
  }

  test("the live check prefills name and version; Check runs the dry run and reports the prediction", async () => {
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/import/check": { status: 200, body: { results: [{ index: 0, name: "Orders", version: "1.1.0", status: "VERSION_ADDED", contractId: 5, versionId: null, message: null, errors: 0, warnings: 0 }] } },
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportContract />);
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    await pasteAndPickSystem();
    await user.click(screen.getByRole("button", { name: "Check" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/import/check")).toBeDefined());
    const body = bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/import/check")) as { items: unknown[] };
    expect(body.items).toEqual([{ systemId: 7, type: "OPENAPI", name: "Orders", description: null, ownerTeamId: null, ownerUserId: null, version: "1.1.0", sourceUrl: null, content: CONTENT }]);
    expect(await screen.findByText("Would add this version")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the contract" })).toHaveAttribute("href", "/contracts/5");
  });

  test("Import stores and links the new version; a refused row keeps its message", async () => {
    let calls = 0;
    serve(mockFetch, {
      ...base,
      "POST /api/v1/contracts/import": () => {
        calls += 1;
        return calls === 1
          ? { status: 200, body: { results: [{ index: 0, name: "Orders", version: "1.1.0", status: "CREATED_WITH_FINDINGS", contractId: 9, versionId: 21, message: "OAS_PARSE: paths is required", errors: 1, warnings: 0 }] } }
          : { status: 200, body: { results: [{ index: 0, name: "Orders", version: "1.1.0", status: "FORBIDDEN", contractId: null, versionId: null, message: "not a writer", errors: 0, warnings: 0 }] } };
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<ImportContract />);
    await pasteAndPickSystem();
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Payments Team" }));
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByText("Contract created — the version carries findings")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open the version" })).toHaveAttribute("href", "/contracts/9/versions/21");
    expect((bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/import")) as { items: { ownerTeamId: number }[] }).items[0].ownerTeamId).toBe(3);
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByText("Not imported — you cannot write this contract")).toBeInTheDocument();
    expect(screen.getByText("not a writer")).toBeInTheDocument();
  });

  test("a document that does not parse blocks both buttons; a 400 on the batch renders the vocabulary", async () => {
    serve(mockFetch, { ...base, "POST /api/v1/contracts/versions/check": { status: 200, body: { ...CLEAN_REPORT, title: null, declaredVersion: null, findings: [{ severity: "ERROR", source: "SYNTAX", code: "YAML_PARSE", message: "bad indent", line: 1, column: 1 }], errors: 1 } } });
    const user = userEvent.setup();
    const { unmount } = renderWithProviders(<ImportContract />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Contract document" }), { target: { value: "openapi: [" } });
    expect(await screen.findByText("bad indent")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Import" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Check" })).toBeDisabled();
    unmount();
    serve(mockFetch, { ...base, "POST /api/v1/contracts/import": { status: 400, body: { title: "Bad Request", status: 400 } } });
    renderWithProviders(<ImportContract />);
    await pasteAndPickSystem();
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByText("The request was refused — check the fields")).toBeInTheDocument();
  });

  test("a seeded document (Infer's hand-off) prefills the text and the type; a plain visit still starts blank", async () => {
    serve(mockFetch, base);
    renderWithProviders(<ImportContract />, { route: "/contracts/import", state: { content: CONTENT, sourceUrl: null, type: "ASYNCAPI" } });
    expect(await screen.findByRole("textbox", { name: "Contract document" })).toHaveValue(CONTENT);
    expect(screen.getByRole("combobox", { name: "Type" })).toHaveValue("AsyncAPI");
  });

  test("validation stops a submit without a system", async () => {
    serve(mockFetch, base);
    const user = userEvent.setup();
    renderWithProviders(<ImportContract />);
    fireEvent.change(await screen.findByRole("textbox", { name: "Contract document" }), { target: { value: CONTENT } });
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue("Orders"));
    await user.click(screen.getByRole("button", { name: "Import" }));
    expect(await screen.findByText("Pick a system")).toBeInTheDocument();
    expect(findCall(mockFetch, "POST", "/api/v1/contracts/import")).toBeUndefined();
  });
});
