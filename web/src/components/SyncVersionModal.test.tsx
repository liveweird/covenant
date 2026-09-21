import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import SyncVersionModal from "./SyncVersionModal";
import NewVersion from "../pages/NewVersion";
import type { VersionResponse } from "../api/versions";
import { bodyOf, calledUrl, CLEAN_REPORT, CONTENT, CONTRACT, findCall, serve, signIn, VERSION, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";

vi.mock("./LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const SOURCE = "https://github.com/acme/contracts/blob/main/orders.yaml";
const LINKED: VersionResponse = { ...VERSION, sourceUrl: SOURCE, lastSyncedAt: 2 };
const REPO_COPY = CONTENT.replace("paths: {}", "paths:\n  /orders: {}");

function renderModal(version: VersionResponse, onSynced = vi.fn(), onClose = vi.fn()) {
  renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/versions/new" element={<NewVersion />} />
      <Route path="*" element={<SyncVersionModal contract={CONTRACT} version={version} onClose={onClose} onSynced={onSynced} />} />
    </Routes>,
    { route: "/contracts/5/versions/11" },
  );
  return { onSynced, onClose };
}

const routes = (repo: string) => ({
  "POST /api/v1/contracts/fetch": { status: 200, body: { content: repo } },
  "GET /api/v1/contracts/5/versions/11/sync": { status: 200, body: { sourceUrl: SOURCE, lastSyncedAt: 2, syncedContent: CONTENT } },
  "POST /api/v1/contracts/versions/check": { status: 200, body: CLEAN_REPORT },
  "POST /api/v1/contracts/5/versions/11/sync": { status: 200, body: { ...LINKED, content: repo } },
});

describe("SyncVersionModal", () => {
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

  test("fetches the repo copy through the server, attributes the change to the repo, diffs, and overwrites on confirm", async () => {
    serve(mockFetch, routes(REPO_COPY));
    const { onSynced, onClose } = renderModal(LINKED);
    expect(await screen.findByText("Changed in the repository")).toBeInTheDocument();
    expect(screen.queryByText("Edited in Covenant")).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Changes between the stored document and the repository copy" })).toHaveTextContent(/- paths: \{\}\+ paths:\+ \/orders: \{\}/);
    expect(screen.getByText(/Syncing overwrites the stored document/)).toBeInTheDocument();
    // The fetch went through the guarded server route with the blob URL normalized to raw.
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/fetch"))).toEqual({ url: "https://raw.githubusercontent.com/acme/contracts/main/orders.yaml" });
    await userEvent.setup().click(screen.getByRole("button", { name: "Overwrite stored document" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/sync")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/sync"))).toEqual({ content: REPO_COPY, sourceUrl: SOURCE });
    await waitFor(() => expect(onSynced).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });

  test("an identical copy is in sync and cannot be overwritten; a local edit since the sync is attributed too", async () => {
    serve(mockFetch, routes(CONTENT));
    renderModal(LINKED);
    expect(await screen.findByText("Covenant and the repository are in sync")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored document" })).toBeDisabled();
  });

  test("changing the source loads a new copy and sends the reference paired with that copy", async () => {
    const nextSource = "https://example.test/next.yaml";
    const nextCopy = REPO_COPY.replace("/orders", "/next-orders");
    serve(mockFetch, routes(REPO_COPY));
    const props = { contract: CONTRACT, onClose: vi.fn(), onSynced: vi.fn() };
    const view = renderWithProviders(<SyncVersionModal {...props} version={LINKED} />);
    expect(await screen.findByText("Changed in the repository")).toBeInTheDocument();
    serve(mockFetch, routes(nextCopy));
    view.rerender(<SyncVersionModal {...props} version={{ ...LINKED, sourceUrl: nextSource }} />);
    await waitFor(() => expect(screen.getByRole("group", { name: "Changes between the stored document and the repository copy" })).toHaveTextContent("/next-orders"));
    await userEvent.setup().click(screen.getByRole("button", { name: "Overwrite stored document" }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/sync")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/sync"))).toEqual({ content: nextCopy, sourceUrl: nextSource });
  });

  test("a failed repository check blocks confirmation instead of presenting an empty findings list", async () => {
    serve(mockFetch, { ...routes(REPO_COPY), "POST /api/v1/contracts/versions/check": { status: 500, body: { detail: "unavailable" } } });
    renderModal(LINKED);
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored document" })).toBeDisabled();
  });

  test("a published old-line sync opens a real new-version page in its source major with the repo copy", async () => {
    const newerMajor = { ...VERSION_PAGE.items[0], id: 20, version: "2.0.0", lifecycle: "ACTIVE" as const };
    serve(mockFetch, {
      ...routes(REPO_COPY),
      "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, latestVersion: newerMajor } },
      "GET /api/v1/contracts/5/versions?": (url) => {
        const major = new URL(url, "http://covenant.local").searchParams.get("major");
        return major === "1"
          ? { status: 200, body: VERSION_PAGE }
          : { status: 200, body: { ...VERSION_PAGE, items: [newerMajor, ...VERSION_PAGE.items], total: 3 } };
      },
    });
    renderModal({ ...LINKED, lifecycle: "ACTIVE", updatedAt: 3 });
    expect(await screen.findByText("Edited in Covenant")).toBeInTheDocument();
    expect(screen.getByText(/This version is published/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Overwrite stored document" })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "New version from the repository copy" }));
    expect(await screen.findByRole("heading", { name: "New version of orders-api" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Version")).toHaveValue("1.1.1"));
    expect(screen.getByRole("textbox", { name: "Contract document" })).toHaveValue(REPO_COPY);
    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/contracts/5/versions?") && url.includes("major=1"))).toBeDefined();
  });

  test("a refused fetch and an unparseable repo copy are errors, never a sync", async () => {
    serve(mockFetch, { ...routes(REPO_COPY), "POST /api/v1/contracts/fetch": { status: 400, body: { detail: "blocked" } } });
    const first = renderWithProviders(<SyncVersionModal contract={CONTRACT} version={LINKED} onClose={vi.fn()} onSynced={vi.fn()} />);
    expect(await screen.findByText("Only public https:// URLs can be fetched")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored document" })).toBeDisabled();
    first.unmount();
    const hard = { severity: "ERROR", source: "SYNTAX", code: "YAML_PARSE", message: "bad indent", line: 1, column: 1 };
    serve(mockFetch, { ...routes("oops: [\n"), "POST /api/v1/contracts/versions/check": { status: 200, body: { ...CLEAN_REPORT, findings: [hard], errors: 1 } } });
    renderWithProviders(<SyncVersionModal contract={CONTRACT} version={LINKED} onClose={vi.fn()} onSynced={vi.fn()} />);
    expect(await screen.findByText(/does not parse/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored document" })).toBeDisabled();
  });
});
