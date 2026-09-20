import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen } from "@testing-library/react";
import ToadieConnections from "./ToadieConnections";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const ROW = {
  id: 2, name: "Architecture", baseUrl: "https://toadie.internal", browserUrl: "https://toadie.example.com", enabled: true,
  refreshIntervalMinutes: 60, mapping: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
  hasApiKey: true, createdAt: 1, updatedAt: 2, lastAttemptAt: 3, lastSuccessAt: 3, refreshing: false, lastErrorCode: null, stale: false,
};

describe("Toadie connections page", () => {
  beforeEach(() => {
    localStorage.setItem("covenant.auth.token", "token");
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [ROW], page: 1, pageSize: 20, total: 1 }))));
  });
  afterEach(() => { vi.unstubAllGlobals(); localStorage.clear(); });

  test("all authenticated users see sanitized connection health while write controls stay hidden", async () => {
    localStorage.setItem("covenant.auth.roles", "[]");
    renderWithProviders(<ToadieConnections />);
    expect(await screen.findByText("Architecture")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "https://toadie.example.com" })).toHaveAttribute("href", "https://toadie.example.com");
    expect(screen.getByText("Current")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "New connection" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Operations for Architecture" })).not.toBeInTheDocument();
  });

  test("the admin editor distinguishes server and browser URLs and keeps mapping advanced", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "New connection" }));
    const dialog = screen.getByRole("dialog");
    expect(screen.getByLabelText("Backend base URL")).toBeInTheDocument();
    expect(screen.getByLabelText("Browser URL")).toBeInTheDocument();
    expect(screen.getByLabelText("API key")).toBeInTheDocument();
    const advanced = screen.getByRole("button", { name: "Advanced mapping" });
    expect(advanced).toHaveAttribute("aria-expanded", "false");
    await user.click(advanced);
    expect(screen.getByLabelText("Service blueprint")).toHaveValue("service");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findAllByText("Use an absolute http(s) URL without credentials, query or fragment")).toHaveLength(2);
    expect(dialog).toHaveTextContent("An API key is required");
  });

  test("an administrator can queue a manual refresh from the row menu", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Refresh now" }));
    await vi.waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => url === "/api/v1/toadie-connections/2/refresh" && init?.method === "POST")).toBe(true));
  });
});
