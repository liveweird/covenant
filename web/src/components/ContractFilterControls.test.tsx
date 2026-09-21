import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import ContractFilterControls from "./ContractFilterControls";
import { useContractFilterState } from "../hooks/useContractFilterState";
import type { ContractFacets } from "../api/contracts";
import { serve, signIn, SYSTEMS_PAGE, type FetchMock } from "../test/contractsFixtures";

const FACETS: ContractFacets = {
  type: [{ value: "OPENAPI", count: 12 }, { value: "ODCS", count: 1 }],
  lifecycle: [{ value: "ACTIVE", count: 9 }, { value: "NONE", count: 4 }],
  domain: [{ id: 1, name: "Payments", count: 13 }],
  system: [{ id: 7, name: "gateway", count: 13 }],
  ownerTeam: [{ id: 3, name: "Payments Team", count: 13 }],
  hasErrors: { withErrors: 2, clean: 11 },
};

function Harness({ facets, latestVersion }: { facets: ContractFacets | null; latestVersion?: boolean }) {
  const filters = useContractFilterState("test", { latestVersion });
  return <ContractFilterControls filters={filters} facets={facets} />;
}

describe("ContractFilterControls", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
    serve(mockFetch, {
      "GET /api/v1/domains?": { status: 200, body: { items: [{ id: 1, name: "Payments", description: null, systemCount: 1, createdAt: 1, updatedAt: 1 }], page: 1, pageSize: 100, total: 1 } },
      "GET /api/v1/systems?": { status: 200, body: SYSTEMS_PAGE },
      "GET /api/v1/teams?": { status: 200, body: { items: [{ id: 3, name: "Payments Team", description: null, memberCount: 2, createdAt: 1, updatedAt: 1 }], page: 1, pageSize: 100, total: 1 } },
    });
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("with facets every option carries its count — zero for a value the facets omit, never removed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness facets={FACETS} />);
    await user.click(screen.getByLabelText("Type", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "OpenAPI (12)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "ODCS (1)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "AsyncAPI (0)" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(screen.getByLabelText("Lifecycle", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Active (9)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Draft (0)" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    await user.click(screen.getByLabelText("Domain", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Payments (13)" })).toBeInTheDocument();
  });

  test("systems and owner teams beyond page one remain selectable", async () => {
    serve(mockFetch, {
      "GET /api/v1/systems?": (url) => {
        const page = Number(new URL(url, "http://localhost").searchParams.get("page"));
        const items = page === 1
          ? Array.from({ length: 100 }, (_, index) => ({ ...SYSTEMS_PAGE.items[0], id: index + 1, name: `System ${index + 1}` }))
          : [{ ...SYSTEMS_PAGE.items[0], id: 101, name: "Late system" }];
        return { status: 200, body: { items, page, pageSize: 100, total: 101 } };
      },
      "GET /api/v1/teams?": (url) => {
        const page = Number(new URL(url, "http://localhost").searchParams.get("page"));
        const items = page === 1
          ? Array.from({ length: 100 }, (_, index) => ({ id: index + 1, name: `Team ${index + 1}` }))
          : [{ id: 101, name: "Late team" }];
        return { status: 200, body: { items, page, pageSize: 100, total: 101 } };
      },
    });
    const user = userEvent.setup();
    renderWithProviders(<Harness facets={null} />);
    const system = screen.getByLabelText("System", { selector: "input" });
    await user.click(system);
    await user.click(await screen.findByRole("option", { name: "Late system" }));
    expect(system).toHaveValue("Late system");
    const team = screen.getByLabelText("Owning team", { selector: "input" });
    await user.click(team);
    await user.click(await screen.findByRole("option", { name: "Late team" }));
    expect(team).toHaveValue("Late team");
  });

  test("without facets the labels are plain", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness facets={null} />);
    await user.click(screen.getByLabelText("Type", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "OpenAPI" })).toBeInTheDocument();
  });

  test("with latestVersion: false the Lifecycle filter and the only-with-errors switch are hidden (the Errors report owns its own lifecycle filter)", async () => {
    renderWithProviders(<Harness facets={null} latestVersion={false} />);
    expect(await screen.findByLabelText("Type", { selector: "input" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Lifecycle", { selector: "input" })).not.toBeInTheDocument();
    expect(screen.queryByRole("switch", { name: "Only with errors" })).not.toBeInTheDocument();
  });
});
