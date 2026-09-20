import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import { jsonResponse } from "../test/http";
import InferContract from "./InferContract";
import { bodyOf, CONTRACT, findCall, serve, signIn, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const DRAFT_RESPONSE = {
  content: "openapi: 3.1.0\ninfo:\n  title: Inferred API\n  version: 1.0.0\npaths:\n  /orders/{orderId}:\n    get: {}\n",
  format: "yaml",
  notes: [{ severity: "INFO", source: "INFERENCE", code: "INFER_PATH_TEMPLATED", message: "Templated /orders/42 as /orders/{orderId}", path: "/paths/~1orders~1{orderId}" }],
  errors: 0,
  warnings: 0,
  infos: 1,
};

/** A stand-in for the hand-off's destination page — asserts the SeededDocument that reached it. */
function LocationProbe({ label }: { label: string }) {
  const location = useLocation();
  return (
    <div>
      <h2>{label}</h2>
      <pre data-testid="state">{JSON.stringify(location.state)}</pre>
    </div>
  );
}

function InferWithRefetch() {
  const queryClient = useQueryClient();
  return (
    <>
      <button type="button" onClick={() => void queryClient.invalidateQueries({ queryKey: ["contracts"] })}>
        Refetch contracts
      </button>
      <InferContract />
    </>
  );
}

function renderPage(route: string, inferElement = <InferContract />) {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/infer" element={inferElement} />
      <Route path="/contracts/:id/infer" element={inferElement} />
      <Route path="/contracts/import" element={<LocationProbe label="Import probe" />} />
      <Route path="/contracts/:id/versions/new" element={<LocationProbe label="New version probe" />} />
    </Routes>,
    { route },
  );
}

describe("InferContract page", () => {
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

  test("a new contract: pasting an exchange, generating and opening in the editor hands the draft over", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE } });
    const user = userEvent.setup();
    renderPage("/contracts/infer");
    expect(screen.getByRole("heading", { level: 2, name: "Infer a contract" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Generate draft" })).toBeDisabled();

    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(screen.getByText("1 sample")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Generate draft" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByText("Templated /orders/42 as /orders/{orderId}")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Inferred document" })).toHaveValue(DRAFT_RESPONSE.content);
    const body = bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/infer")) as Record<string, unknown>;
    expect(body.type).toBe("OPENAPI");
    expect(body.http as unknown[]).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Open in editor" }));
    expect(await screen.findByText("Import probe")).toBeInTheDocument();
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toEqual({ content: DRAFT_RESPONSE.content, sourceUrl: null, type: "OPENAPI" });
  });

  test("an existing contract: the type is fixed (no picker), and Open in editor hands the draft to its new-version page", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE },
    });
    const user = userEvent.setup();
    renderPage("/contracts/5/infer");
    await screen.findByRole("heading", { level: 2, name: "Infer a contract" });
    // No type picker for an existing contract — its type comes from the contract itself.
    expect(screen.queryByRole("radiogroup")).not.toBeInTheDocument();

    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    await screen.findByText("Templated /orders/42 as /orders/{orderId}");

    await user.click(screen.getByRole("button", { name: "Open in editor" }));
    expect(await screen.findByText("New version probe")).toBeInTheDocument();
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toEqual({ content: DRAFT_RESPONSE.content, sourceUrl: null, version: "1.1.1" });
  });

  test("an existing contract waits for its versions before choosing the inference default", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE },
    });
    const baseFetch = mockFetch.getMockImplementation();
    let resolveVersions!: (response: Response) => void;
    const delayedVersions = new Promise<Response>((resolve) => {
      resolveVersions = resolve;
    });
    mockFetch.mockImplementation((url: string, init?: RequestInit) =>
      url.startsWith("/api/v1/contracts/5/versions?") ? delayedVersions : baseFetch?.(url, init),
    );

    const user = userEvent.setup();
    renderPage("/contracts/5/infer");
    expect(screen.getByLabelText("Loading…")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Generate draft" })).not.toBeInTheDocument();

    resolveVersions(jsonResponse(200, VERSION_PAGE));
    expect(await screen.findByLabelText("Version")).toHaveValue("1.1.1");
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    await screen.findByText("Templated /orders/42 as /orders/{orderId}");
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/infer"))).toMatchObject({ version: "1.1.1" });
  });

  test("an existing contract renders a versions prerequisite failure", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 500, body: { title: "Internal Server Error", status: 500 } },
    });
    renderPage("/contracts/5/infer");
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Generate draft" })).not.toBeInTheDocument();
  });

  test("metadata edits invalidate the draft and the selected version reaches an existing contract's editor", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE },
    });
    const user = userEvent.setup();
    renderPage("/contracts/5/infer");
    await user.type(await screen.findByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    const version = screen.getByLabelText("Version");
    await user.clear(version);
    await user.type(version, "9.0.0");
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByText("Templated /orders/42 as /orders/{orderId}")).toBeInTheDocument();

    await user.clear(version);
    await user.type(version, "10.0.0");
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeDisabled();
    expect(screen.queryByText("Templated /orders/42 as /orders/{orderId}")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    await user.click(await screen.findByRole("button", { name: "Open in editor" }));
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toMatchObject({ version: "10.0.0" });
  });

  test("background contract and version refetches cannot change generated metadata or its handoff", async () => {
    let contractLoads = 0;
    let versionLoads = 0;
    serve(mockFetch, {
      "GET /api/v1/contracts/5": () => {
        contractLoads += 1;
        return { status: 200, body: contractLoads === 1 ? CONTRACT : { ...CONTRACT, name: "renamed-api" } };
      },
      "GET /api/v1/contracts/5/versions?": () => {
        versionLoads += 1;
        return {
          status: 200,
          body: versionLoads === 1
            ? VERSION_PAGE
            : { ...VERSION_PAGE, items: [{ ...VERSION_PAGE.items[0], id: 99, version: "5.0.0" }, ...VERSION_PAGE.items] },
        };
      },
      "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE },
    });
    const user = userEvent.setup();
    renderPage("/contracts/5/infer", <InferWithRefetch />);
    await user.type(await screen.findByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    await screen.findByText("Templated /orders/42 as /orders/{orderId}");

    await user.click(screen.getByRole("button", { name: "Refetch contracts" }));
    await waitFor(() => {
      expect(contractLoads).toBe(2);
      expect(versionLoads).toBe(2);
    });
    expect(screen.getByLabelText("Name")).toHaveValue("orders-api");
    expect(screen.getByLabelText("Version")).toHaveValue("1.1.1");

    await user.click(screen.getByRole("button", { name: "Open in editor" }));
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toMatchObject({ version: "1.1.1" });
  });

  test("changing a new contract's name invalidates its generated draft", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE } });
    const user = userEvent.setup();
    renderPage("/contracts/infer");
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByRole("button", { name: "Open in editor" })).toBeEnabled();
    await user.type(screen.getByLabelText("Name"), "orders");
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeDisabled();
  });

  test("a blank version is normalized consistently for inference and editor handoff", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE },
    });
    const user = userEvent.setup();
    renderPage("/contracts/5/infer");
    const version = await screen.findByLabelText("Version");
    await user.clear(version);
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    await screen.findByText("Templated /orders/42 as /orders/{orderId}");
    expect(version).toHaveValue("1.0.0");
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/infer"))).toMatchObject({ version: "1.0.0" });
    await user.click(screen.getByRole("button", { name: "Open in editor" }));
    expect(JSON.parse(screen.getByTestId("state").textContent ?? "null")).toMatchObject({ version: "1.0.0" });
  });

  test("a 400 from infer is shown inline", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/infer": { status: 400, body: { title: "Bad Request", status: 400 } } });
    const user = userEvent.setup();
    renderPage("/contracts/infer");
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByText("Check the samples below — one of them was refused.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeDisabled();
  });

  test("Generate stays disabled with no samples", async () => {
    serve(mockFetch);
    renderPage("/contracts/infer");
    await waitFor(() => expect(screen.getByRole("button", { name: "Generate draft" })).toBeDisabled());
  });

  test("switching the type after generating a draft clears the preview and disables Open in editor", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE } });
    const user = userEvent.setup();
    renderPage("/contracts/infer");

    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByText("Templated /orders/42 as /orders/{orderId}")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Inferred document" })).toHaveValue(DRAFT_RESPONSE.content);
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeEnabled();

    await user.click(screen.getByRole("radio", { name: "ASYNCAPI" }));
    expect(screen.getByRole("textbox", { name: "Inferred document" })).toHaveValue("");
    expect(screen.queryByText("Templated /orders/42 as /orders/{orderId}")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeDisabled();
  });

  test("removing the sample a draft was generated from disables Open in editor", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/infer": { status: 200, body: DRAFT_RESPONSE } });
    const user = userEvent.setup();
    renderPage("/contracts/infer");

    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    await user.click(screen.getByRole("button", { name: "Generate draft" }));
    expect(await screen.findByText("Templated /orders/42 as /orders/{orderId}")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: /^Remove/ }));
    expect(screen.getByRole("button", { name: "Open in editor" })).toBeDisabled();
  });

  test("switching the type resets the samples; a HAR upload and a removal both work", async () => {
    serve(mockFetch);
    const user = userEvent.setup();
    renderPage("/contracts/infer");

    // OpenAPI: add via paste, then remove it.
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(screen.getByText("1 sample")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^Remove/ }));
    expect(screen.getByText(/No samples yet/)).toBeInTheDocument();

    // OpenAPI: add via the HAR tab too.
    await user.click(screen.getByRole("tab", { name: "HAR file" }));
    const har = {
      log: {
        version: "1.2",
        entries: [
          {
            request: { method: "GET", url: "https://api.example.test/orders/1", headers: [], queryString: [] },
            response: { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: "{}" } },
          },
        ],
      },
    };
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File([JSON.stringify(har)], "export.har", { type: "application/json" });
    Object.defineProperty(input, "files", { value: [file] });
    input.dispatchEvent(new Event("change", { bubbles: true }));
    await user.click(await screen.findByRole("button", { name: "Add" }));
    expect(await screen.findByText("1 sample")).toBeInTheDocument();

    // Switching the type wipes the OpenAPI-only samples.
    await user.click(screen.getByRole("radio", { name: "ASYNCAPI" }));
    expect(screen.getByText(/No samples yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: "Paste" }));
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: '{"a":1}' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(screen.getByText("1 sample")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^Remove/ }));

    await user.click(screen.getByRole("radio", { name: "ODCS" }));
    expect(screen.getByText(/No samples yet/)).toBeInTheDocument();
    await user.type(screen.getByLabelText("Relation"), "public.users");
    fireEvent.change(screen.getByRole("textbox", { name: "Rows" }), { target: { value: '[{"id":1}]' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(screen.getByText("1 sample")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^Remove/ }));
    expect(screen.getByText(/No samples yet/)).toBeInTheDocument();
  });
});
