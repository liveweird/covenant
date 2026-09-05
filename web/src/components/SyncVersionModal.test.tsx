import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen, waitFor } from "../test/render";
import SyncVersionModal from "./SyncVersionModal";
import type { VersionResponse } from "../api/versions";
import { bodyOf, CLEAN_REPORT, CONTENT, CONTRACT, findCall, serve, signIn, VERSION, type FetchMock } from "../test/contractsFixtures";

const SOURCE = "https://github.com/acme/contracts/blob/main/orders.yaml";
const LINKED: VersionResponse = { ...VERSION, sourceUrl: SOURCE, lastSyncedAt: 2 };
const REPO_COPY = CONTENT.replace("paths: {}", "paths:\n  /orders: {}");

function renderModal(version: VersionResponse, onSynced = vi.fn(), onClose = vi.fn()) {
  renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/versions/new" element={<h2>New version page</h2>} />
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
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/sync"))).toEqual({ content: REPO_COPY });
    await waitFor(() => expect(onSynced).toHaveBeenCalled());
    expect(onClose).toHaveBeenCalled();
  });

  test("an identical copy is in sync and cannot be overwritten; a local edit since the sync is attributed too", async () => {
    serve(mockFetch, routes(CONTENT));
    renderModal(LINKED);
    expect(await screen.findByText("Covenant and the repository are in sync")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overwrite stored document" })).toBeDisabled();
  });

  test("a published version offers a new version seeded with the repo copy instead of an overwrite", async () => {
    serve(mockFetch, routes(REPO_COPY));
    renderModal({ ...LINKED, lifecycle: "ACTIVE", updatedAt: 3 });
    expect(await screen.findByText("Edited in Covenant")).toBeInTheDocument();
    expect(screen.getByText(/This version is published/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Overwrite stored document" })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole("button", { name: "New version from the repository copy" }));
    expect(await screen.findByRole("heading", { name: "New version page" })).toBeInTheDocument();
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
