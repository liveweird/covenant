import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { Route, Routes, useLocation } from "react-router-dom";
import { renderWithProviders, screen } from "../test/render";
import CommandPalette from "./CommandPalette";
import { palette } from "../utils/commandPalette";

function Probe() {
  const { pathname } = useLocation();
  return <p>at {pathname}</p>;
}

function renderPalette() {
  return renderWithProviders(
    <>
      <CommandPalette />
      <Routes>
        <Route path="*" element={<Probe />} />
      </Routes>
    </>,
  );
}

describe("CommandPalette", () => {
  beforeEach(() => {
    localStorage.setItem("covenant.auth.token", "fake-token");
  });

  afterEach(() => {
    palette.close();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the header trigger opens the palette listing the session's pages", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    await user.click(screen.getAllByRole("button", { name: "Search and jump to…" })[0]);
    expect(await screen.findByRole("button", { name: /Hierarchy/ })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Change password/ })).toBeInTheDocument();
    // A regular session never sees the admin pages.
    expect(screen.queryByRole("button", { name: /^Users$/ })).not.toBeInTheDocument();
  });

  test("an admin session's palette includes the Administration pages, and a pick navigates", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    vi.stubGlobal("fetch", vi.fn());
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Users/ }));
    expect(await screen.findByText("at /users")).toBeInTheDocument();
  });

  test("the actions group jumps to New contract / Import; two characters search contracts server-side", async () => {
    const mockFetch = vi.fn((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.includes("q=ord")
              ? { items: [{ id: 5, name: "orders-api", type: "OPENAPI", system: { id: 7, name: "gateway" }, domain: { id: 1, name: "Payments" }, owner: { kind: "TEAM", id: 3, name: "T", deleted: false }, latestVersion: null, versionCount: 0, canWrite: false, createdBy: 1, createdAt: 1, updatedAt: 1 }], page: 1, pageSize: 8, total: 1 }
              : { items: [], page: 1, pageSize: 8, total: 0 },
          ),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      ),
    );
    vi.stubGlobal("fetch", mockFetch);
    const user = userEvent.setup();
    renderPalette();
    palette.open();
    await user.click(await screen.findByRole("button", { name: /Import a document/ }));
    expect(await screen.findByText("at /contracts/import")).toBeInTheDocument();
    palette.open();
    await user.type(await screen.findByRole("textbox"), "ord");
    expect(await screen.findByRole("button", { name: /orders-api/ })).toBeInTheDocument();
    expect(mockFetch.mock.calls.some(([u]) => typeof u === "string" && u.startsWith("/api/v1/contracts?") && u.includes("q=ord"))).toBe(true);
    await user.click(screen.getByRole("button", { name: /orders-api/ }));
    expect(await screen.findByText("at /contracts/5")).toBeInTheDocument();
  });
});
