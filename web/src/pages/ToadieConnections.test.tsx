import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { screen, waitFor, within } from "@testing-library/react";
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
    expect(screen.getByLabelText("API or dataset blueprint")).toHaveValue("api");
    expect(screen.getByRole("button", { name: "Use API mapping" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Use dataset mapping" })).toBeInTheDocument();
    expect(dialog).toHaveTextContent("ODCS contracts need the dataset relations from the Toadie 2.13 ontology");
    await user.click(screen.getByRole("button", { name: "Create" }));
    expect(await screen.findAllByText("Use an absolute http(s) URL without credentials, query or fragment")).toHaveLength(2);
    expect(dialog).toHaveTextContent("An API key is required");
  });

  test("the dataset preset creates a connection with the baseline dataset ontology mapping", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "New connection" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Datasets");
    await user.type(within(dialog).getByLabelText("Backend base URL"), "https://toadie.internal");
    await user.type(within(dialog).getByLabelText("Browser URL"), "https://toadie.example.com");
    await user.type(within(dialog).getByLabelText("API key"), "secret");
    await user.click(within(dialog).getByRole("button", { name: "Advanced mapping" }));
    await user.click(within(dialog).getByRole("button", { name: "Use dataset mapping" }));
    await user.click(within(dialog).getByRole("button", { name: "Create" }));

    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([url, init]) => url === "/api/v1/toadie-connections" && init?.method === "POST");
      const body = JSON.parse((call?.[1] as RequestInit).body as string);
      expect(body.mapping).toEqual({
        serviceBlueprint: "service",
        apiBlueprint: "dataset",
        providesRelation: "produces_datasets",
        consumesRelation: "consumes_datasets",
        systemRelation: "system",
      });
      expect(body.adoptionMapping).toBeNull();
    });
  });

  test("declared adoption is explicitly enabled and its preset remains editable", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "New connection" }));
    const dialog = screen.getByRole("dialog");
    await user.type(within(dialog).getByLabelText("Name"), "Adoption");
    await user.type(within(dialog).getByLabelText("Backend base URL"), "https://toadie.internal");
    await user.type(within(dialog).getByLabelText("Browser URL"), "https://toadie.example.com");
    await user.type(within(dialog).getByLabelText("API key"), "secret");
    await user.click(within(dialog).getByRole("button", { name: "Declared adoption mapping" }));
    expect(within(dialog).getByRole("switch", { name: /^Read declared adoption/ })).not.toBeChecked();
    await user.click(within(dialog).getByRole("switch", { name: /^Read declared adoption/ }));
    await user.click(within(dialog).getByRole("button", { name: "Use dataset adoption mapping" }));
    expect(within(dialog).getByLabelText("Adoption blueprint")).toHaveValue("dataset_adoption");
    expect(within(dialog).getByLabelText("Adoption value property")).toHaveValue("contract_version");
    await user.clear(within(dialog).getByLabelText("Notes property"));
    await user.click(within(dialog).getByRole("button", { name: "Create" }));
    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([url, init]) => url === "/api/v1/toadie-connections" && init?.method === "POST");
      expect(JSON.parse((call?.[1] as RequestInit).body as string).adoptionMapping).toEqual({
        blueprint: "dataset_adoption", kind: "DATASET_CONTRACT_VERSION", consumerRelation: "consumer", targetRelation: "dataset",
        environmentRelation: "environment", valueProperty: "contract_version", statusProperty: "status", declaredByProperty: "declared_by",
        verifiedAtProperty: "verified_at", notesProperty: null,
      });
    });
  });

  test("a saved custom mapping changes only on preset click and remains editable with registry settings and credentials preserved", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const customRow = {
      ...ROW,
      mapping: { serviceBlueprint: "workload", apiBlueprint: "data-product", providesRelation: "publishes", consumesRelation: "reads", systemRelation: "belongs_to" },
      registryMapping: {
        domainBlueprint: "business-domain",
        systemDomainRelation: "domain",
        domainParentRelation: "parent_domain",
        flattenDomains: true,
        domainDescriptionProperty: "summary",
        systemDescriptionProperty: null,
        teamDescriptionProperty: null,
      },
    };
    vi.stubGlobal("fetch", vi.fn(() => Promise.resolve(jsonResponse(200, { items: [customRow], page: 1, pageSize: 20, total: 1 }))));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("API key"), "replacement");
    await user.click(within(dialog).getByRole("button", { name: "Advanced mapping" }));
    expect(within(dialog).getByLabelText("Service blueprint")).toHaveValue("workload");
    expect(within(dialog).getByLabelText("API or dataset blueprint")).toHaveValue("data-product");
    expect(within(dialog).getByLabelText("Provides or produces relation")).toHaveValue("publishes");
    await user.click(within(dialog).getByRole("button", { name: "Use dataset mapping" }));
    expect(within(dialog).getByLabelText("API or dataset blueprint")).toHaveValue("dataset");
    const blueprint = within(dialog).getByLabelText("API or dataset blueprint");
    await user.clear(blueprint);
    await user.type(blueprint, "custom-dataset");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() => {
      const call = vi.mocked(fetch).mock.calls.find(([url, init]) => url === "/api/v1/toadie-connections/2" && init?.method === "PUT");
      const body = JSON.parse((call?.[1] as RequestInit).body as string);
      expect(body.apiKey).toBe("replacement");
      expect(body.mapping).toEqual({
        serviceBlueprint: "service",
        apiBlueprint: "custom-dataset",
        providesRelation: "produces_datasets",
        consumesRelation: "consumes_datasets",
        systemRelation: "system",
      });
      expect(body.registryMapping).toEqual(customRow.registryMapping);
    });
  });

  test("an administrator can queue a manual refresh from the row menu", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Refresh now" }));
    await vi.waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => url === "/api/v1/toadie-connections/2/refresh" && init?.method === "POST")).toBe(true));
  });

  test("an administrator explicitly confirms connection deletion", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Delete" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => url === "/api/v1/toadie-connections/2" && init?.method === "DELETE")).toBe(true));
  });

  test("an administrator explicitly acknowledges flat domains before enabling registry sync", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Edit" }));
    const dialog = await screen.findByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Registry metadata mapping" }));
    await user.click(within(dialog).getByRole("switch", { name: /^Enable registry metadata sync/ }));
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("Acknowledge that nested Toadie domains will be flattened")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("checkbox", { name: "I understand that Toadie domain nesting will be flattened" }));
    await user.type(within(dialog).getByLabelText("Domain description property"), "summary");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => url === "/api/v1/toadie-connections/2" && init?.method === "PUT")).toBe(true));
    const call = vi.mocked(fetch).mock.calls.find(([url, init]) => url === "/api/v1/toadie-connections/2" && init?.method === "PUT");
    const body = JSON.parse((call?.[1] as RequestInit).body as string);
    expect(body.registryMapping).toEqual({
      domainBlueprint: "domain",
      systemDomainRelation: "domain",
      domainParentRelation: "parent_domain",
      flattenDomains: true,
      domainDescriptionProperty: "summary",
      systemDescriptionProperty: null,
      teamDescriptionProperty: null,
    });
  });

  test("an unconfigured registry sync directs the administrator to the expanded mapping editor", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await user.click(await screen.findByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: "Sync registry metadata" }));
    const setupDialog = await screen.findByRole("dialog");
    expect(within(setupDialog).getByText("Configure registry metadata mapping on this connection before synchronizing.")).toBeInTheDocument();
    await user.click(within(setupDialog).getByRole("button", { name: "Configure registry sync" }));
    const editor = await screen.findByRole("dialog");
    expect(within(editor).getByRole("button", { name: "Registry metadata mapping" })).toHaveAttribute("aria-expanded", "true");
    expect(within(editor).getByRole("switch", { name: /^Enable registry metadata sync/ })).not.toBeChecked();
    expect(within(editor).queryByRole("checkbox", { name: "I understand that Toadie domain nesting will be flattened" })).not.toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls.some(([url]) => typeof url === "string" && (url.includes("registry-candidates") || url.startsWith("/api/v1/domains?") || url.startsWith("/api/v1/systems?") || url.startsWith("/api/v1/teams?")))).toBe(false);
  });

  test("a pending initial sort cannot restore a connection deleted from placeholder rows", async () => {
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    const page = { items: [ROW], page: 1, pageSize: 20, total: 1 };
    let release!: (response: Response) => void;
    const stale = new Promise<Response>((resolve) => { release = resolve; });
    let reads = 0;
    vi.stubGlobal("fetch", vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "DELETE") return Promise.resolve(new Response(null, { status: 204 }));
      if (new URL(url, "http://localhost").searchParams.get("sort") === "-name") {
        reads += 1;
        return reads === 1 ? stale : Promise.resolve(jsonResponse(200, { ...page, items: [], total: 0 }));
      }
      return Promise.resolve(jsonResponse(200, page));
    }));
    const user = userEvent.setup();
    renderWithProviders(<ToadieConnections />);
    await screen.findByText("Architecture");
    await user.click(screen.getByRole("button", { name: /^Name$/ }));
    await waitFor(() => expect(reads).toBe(1));
    await user.click(screen.getByRole("button", { name: "Operations for Architecture" }));
    await user.click(await screen.findByRole("menuitem", { name: /^Delete$/ }));
    await user.click(within(screen.getByRole("dialog")).getByRole("button", { name: /^Delete$/ }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    release(jsonResponse(200, page));
    await waitFor(() => expect(reads).toBe(2));
    await waitFor(() => expect(screen.queryByText("Architecture")).not.toBeInTheDocument());
    expect(screen.getByRole("columnheader", { name: "Name" })).toHaveAttribute("aria-sort", "descending");
  });

});
