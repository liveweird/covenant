import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor } from "@testing-library/react";
import ToadieLinksModal from "./ToadieLinksModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";

const EMPTY_LINKS = {
  contractId: 5,
  connection: { id: 1, name: "Toadie A", browserUrl: "https://a.example.test" },
  cache: { state: "CURRENT" as const, lastAttemptAt: 1, lastSuccessAt: 1, refreshing: false, lastErrorCode: null },
  items: [],
};

describe("ToadieLinksModal", () => {
  let mockFetch: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.setItem("covenant.auth.token", "token");
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a connection switch cannot select placeholder rows from the previous connection", async () => {
    let resolveB!: (response: Response) => void;
    const bApis = new Promise<Response>((resolve) => { resolveB = resolve; });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/toadie-connections?")) {
        return Promise.resolve(jsonResponse(200, {
          items: [{ id: 1, name: "Toadie A" }, { id: 2, name: "Toadie B" }],
          page: 1,
          pageSize: 20,
          total: 2,
        }));
      }
      if (url.includes("/toadie-connections/1/apis?")) {
        return Promise.resolve(jsonResponse(200, {
          items: [{ entityId: "shared-id", identifier: "orders-a", title: "Orders A", url: null }],
          page: 1,
          pageSize: 20,
          total: 1,
        }));
      }
      if (url.includes("/toadie-connections/2/apis?")) return bApis;
      if (url === "/api/v1/contracts/5/toadie-links" && init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    });

    const user = userEvent.setup();
    renderWithProviders(<ToadieLinksModal contractId={5} links={EMPTY_LINKS} onClose={vi.fn()} onSaved={vi.fn()} />);
    expect(screen.getByRole("textbox", { name: "Search APIs or datasets" })).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Available APIs or datasets" })).toBeInTheDocument();
    expect(await screen.findByRole("checkbox", { name: "Orders A" })).toBeInTheDocument();
    await user.click(screen.getByRole("combobox", { name: "Connection" }));
    await user.click(await screen.findByRole("option", { name: "Toadie B" }));

    expect(screen.queryByRole("checkbox", { name: "Orders A" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save links" })).toBeDisabled();

    resolveB(jsonResponse(200, {
      items: [{ entityId: "shared-id", identifier: "orders-b", title: "Orders B", url: null }],
      page: 1,
      pageSize: 20,
      total: 1,
    }));
    const bRow = await screen.findByRole("checkbox", { name: "Orders B" });
    expect(bRow).not.toBeChecked();
    await user.click(bRow);
    await user.click(screen.getByRole("button", { name: "Save links" }));

    await waitFor(() => {
      const call = mockFetch.mock.calls.find(([url, request]) => url === "/api/v1/contracts/5/toadie-links" && (request as RequestInit)?.method === "PUT");
      expect(JSON.parse((call?.[1] as RequestInit).body as string)).toEqual({ connectionId: 2, apiEntityIds: ["shared-id"] });
    });
  });
});
