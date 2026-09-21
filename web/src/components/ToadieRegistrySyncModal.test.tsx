import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ToadieRegistrySyncModal from "./ToadieRegistrySyncModal";
import { jsonResponse } from "../test/http";
import { renderWithProviders } from "../test/render";
import type { ToadieConnection, ToadieRegistryCandidate } from "../api/toadie";

type FetchMock = ReturnType<typeof vi.fn>;
const CACHE = { state: "CURRENT", lastAttemptAt: 10, lastSuccessAt: 10, refreshing: false, lastErrorCode: null };
const CONNECTION: ToadieConnection = {
  id: 3,
  name: "Architecture",
  baseUrl: "https://toadie.internal",
  browserUrl: "https://toadie.example",
  enabled: true,
  refreshIntervalMinutes: 60,
  mapping: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
  registryMapping: { domainBlueprint: "domain", systemDomainRelation: "domain", domainParentRelation: "parent_domain", flattenDomains: true, domainDescriptionProperty: null, systemDescriptionProperty: null, teamDescriptionProperty: null },
  hasApiKey: true,
  createdAt: 1,
  updatedAt: 2,
  lastAttemptAt: 10,
  lastSuccessAt: 10,
  refreshing: false,
  lastErrorCode: null,
  stale: false,
};
const DOMAIN = { entityId: "7", identifier: "payments", title: "Remote Payments", description: "Remote", remoteUpdatedAt: 5, parentEntityId: "8", parentIdentifier: "commerce", parentTitle: "Commerce", linkedLocalId: null, linkedLocalName: null, fallbackDomainId: null, issues: [] };
const SYSTEM = { entityId: "9", identifier: "gateway", title: "Remote Gateway", description: null, remoteUpdatedAt: 5, parentEntityId: null, parentIdentifier: null, parentTitle: null, linkedLocalId: null, linkedLocalName: null, fallbackDomainId: null, issues: [] };
const DOMAINS = { items: [
  { id: 1, name: "Payments", description: null, systemCount: 1, createdAt: 1, updatedAt: 1 },
  { id: 6, name: "Platform", description: null, systemCount: 1, createdAt: 1, updatedAt: 1 },
], page: 1, pageSize: 100, total: 2 };
const SYSTEMS = { items: [
  { id: 4, domainId: 1, domainName: "Payments", name: "gateway", description: null, contractCount: 0, createdAt: 1, updatedAt: 1 },
  { id: 5, domainId: 2, domainName: "Identity", name: "gateway", description: null, contractCount: 0, createdAt: 1, updatedAt: 1 },
], page: 1, pageSize: 100, total: 2 };

function candidatePage(item: ToadieRegistryCandidate) {
  return { items: [item], page: 1, pageSize: 20, total: 1, cache: CACHE };
}

function bodyOf(mockFetch: FetchMock, suffix: string) {
  const call = mockFetch.mock.calls.find(([url, init]) => typeof url === "string" && url.endsWith(suffix) && (init as RequestInit | undefined)?.method === "POST");
  return JSON.parse((call?.[1] as RequestInit).body as string);
}

describe("Toadie registry sync modal", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
  });
  afterEach(() => vi.unstubAllGlobals());

  test("does not load registry data before the connection is configured", async () => {
    const configure = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={{ ...CONNECTION, registryMapping: null }} onClose={vi.fn()} onConfigure={configure} onApplied={vi.fn()} />);
    expect(screen.getByText("Configure registry metadata mapping on this connection before synchronizing.")).toBeInTheDocument();
    expect(mockFetch).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Configure registry sync" }));
    expect(configure).toHaveBeenCalledOnce();
    expect(mockFetch).not.toHaveBeenCalled();
  });

  test("stops the loading state after a candidate failure and retries the current kind", async () => {
    let attempts = 0;
    mockFetch.mockImplementation((url: string) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.includes("registry-candidates")) {
        attempts += 1;
        return Promise.resolve(attempts === 1 ? jsonResponse(500, { title: "failed", status: 500 }) : jsonResponse(200, candidatePage(DOMAIN)));
      }
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    expect(await screen.findByText("Load failed (500)")).toBeInTheDocument();
    expect(screen.queryByRole("status", { name: "Loading" })).not.toBeInTheDocument();
    expect(screen.queryByText("No Toadie records match this view")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Retry loading records" }));
    expect(await screen.findByText("Remote Payments")).toBeInTheDocument();
    expect(attempts).toBe(2);
  });

  test("previews an explicit domain link before applying the frozen plan", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.includes("registry-candidates") && url.includes("kind=DOMAIN")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return Promise.resolve(jsonResponse(200, {
        kind: "DOMAIN", cache: CACHE, planToken: "plan-1", canApply: true,
        items: [{ entityId: "7", localId: 1, action: "LINK", before: { name: "Payments", description: null, domainId: null }, after: { name: "Remote Payments", description: "Remote", domainId: null }, issues: [] }],
      }));
      if (url.endsWith("/registry-sync") && init?.method === "POST") return Promise.resolve(jsonResponse(200, { kind: "DOMAIN", items: [{ entityId: "7", localId: 1, action: "LINK" }] }));
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const applied = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={applied} />);
    await user.click(await screen.findByRole("checkbox", { name: "Remote Payments" }));
    fireEvent.click(screen.getByRole("combobox", { name: "Covenant target Remote Payments" }));
    await user.click(await screen.findByRole("option", { name: "Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    expect(await screen.findByRole("region", { name: "Synchronization preview" })).toHaveTextContent("Link");
    expect(bodyOf(mockFetch, "/registry-sync/preview")).toEqual({ kind: "DOMAIN", items: [{ entityId: "7", localId: 1 }] });
    await user.click(screen.getByRole("button", { name: "Apply preview" }));
    await waitFor(() => expect(applied).toHaveBeenCalled());
    expect(bodyOf(mockFetch, "/registry-sync")).toEqual({ kind: "DOMAIN", items: [{ entityId: "7", localId: 1 }], expectedPlanToken: "plan-1" });
  });

  test("disambiguates systems, sends a domainless fallback, and names preview domains", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.startsWith("/api/v1/systems?")) return Promise.resolve(jsonResponse(200, SYSTEMS));
      if (url.includes("registry-candidates") && url.includes("kind=DOMAIN")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.includes("registry-candidates") && url.includes("kind=SYSTEM")) return Promise.resolve(jsonResponse(200, candidatePage(SYSTEM)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return Promise.resolve(jsonResponse(200, {
        kind: "SYSTEM", cache: CACHE, planToken: "p", canApply: true,
        items: [{ entityId: "9", localId: 4, action: "UPDATE", before: { name: "gateway", description: null, domainId: 1 }, after: { name: "Remote Gateway", description: null, domainId: 6 }, issues: [] }],
      }));
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    fireEvent.click(screen.getByRole("combobox", { name: "Registry kind" }));
    await user.click(await screen.findByRole("option", { name: "Systems" }));
    await user.click(await screen.findByRole("checkbox", { name: "Remote Gateway" }));
    fireEvent.click(screen.getByRole("combobox", { name: "Covenant target Remote Gateway" }));
    expect(await screen.findByRole("option", { name: "Payments / gateway" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Identity / gateway" })).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "Payments / gateway" }));
    fireEvent.click(screen.getByRole("combobox", { name: "Fallback domain Remote Gateway" }));
    await user.click(await screen.findByRole("option", { name: "Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    await waitFor(() => expect(bodyOf(mockFetch, "/registry-sync/preview")).toEqual({ kind: "SYSTEM", items: [{ entityId: "9", localId: 4, fallbackDomainId: 1 }] }));
    const preview = await screen.findByRole("region", { name: "Synchronization preview" });
    expect(preview).toHaveTextContent("Domain: Payments");
    expect(preview).toHaveTextContent("Domain: Platform");
    expect(preview).not.toHaveTextContent("#6");
  });

  test("retains a saved fallback when a linked system later gains a source domain", async () => {
    const linkedSystem = { ...SYSTEM, parentEntityId: "7", parentIdentifier: "payments", parentTitle: "Payments", linkedLocalId: 4, linkedLocalName: "gateway", fallbackDomainId: 2 };
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.startsWith("/api/v1/systems?")) return Promise.resolve(jsonResponse(200, SYSTEMS));
      if (url.includes("registry-candidates") && url.includes("kind=DOMAIN")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.includes("registry-candidates") && url.includes("kind=SYSTEM")) return Promise.resolve(jsonResponse(200, candidatePage(linkedSystem)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return Promise.resolve(jsonResponse(200, { kind: "SYSTEM", cache: CACHE, planToken: "p", canApply: true, items: [] }));
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    fireEvent.click(screen.getByRole("combobox", { name: "Registry kind" }));
    await user.click(await screen.findByRole("option", { name: "Systems" }));
    await user.click(await screen.findByRole("checkbox", { name: "Remote Gateway" }));
    expect(screen.queryByRole("combobox", { name: "Fallback domain Remote Gateway" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    await waitFor(() => expect(bodyOf(mockFetch, "/registry-sync/preview")).toEqual({ kind: "SYSTEM", items: [{ entityId: "9", localId: 4, fallbackDomainId: 2 }] }));
  });

  test("hides old-kind placeholder rows and freezes selection while preview is pending", async () => {
    let resolveSystem!: (response: Response) => void;
    let resolvePreview!: (response: Response) => void;
    const systemResponse = new Promise<Response>((resolve) => { resolveSystem = resolve; });
    const previewResponse = new Promise<Response>((resolve) => { resolvePreview = resolve; });
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.startsWith("/api/v1/systems?")) return Promise.resolve(jsonResponse(200, SYSTEMS));
      if (url.includes("registry-candidates") && url.includes("kind=DOMAIN")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.includes("registry-candidates") && url.includes("kind=SYSTEM")) return systemResponse;
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return previewResponse;
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    expect(await screen.findByText("Remote Payments")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("combobox", { name: "Registry kind" }));
    await user.click(await screen.findByRole("option", { name: "Systems" }));
    expect(screen.queryByText("Remote Payments")).not.toBeInTheDocument();
    resolveSystem(jsonResponse(200, candidatePage(SYSTEM)));
    await user.click(await screen.findByRole("checkbox", { name: "Remote Gateway" }));
    fireEvent.click(screen.getByRole("combobox", { name: "Fallback domain Remote Gateway" }));
    await user.click(await screen.findByRole("option", { name: "Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    expect(screen.getByRole("combobox", { name: "Registry kind" })).toBeDisabled();
    expect(screen.getByRole("checkbox", { name: "Remote Gateway" })).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Close" }));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    resolvePreview(jsonResponse(200, { kind: "SYSTEM", cache: CACHE, planToken: "p", canApply: true, items: [] }));
    expect(await screen.findByRole("region", { name: "Synchronization preview" })).toBeInTheDocument();
  });

  test("keeps a conflicting preview read-only and translates its stable issue codes", async () => {
    const conflict = { ...DOMAIN, issues: ["NAME_CONFLICT"] };
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.includes("registry-candidates")) return Promise.resolve(jsonResponse(200, candidatePage(conflict)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return Promise.resolve(jsonResponse(200, {
        kind: "DOMAIN", cache: CACHE, planToken: "blocked", canApply: false,
        items: [{ entityId: "7", localId: null, action: "IMPORT", before: null, after: { name: "Remote Payments", description: "Remote", domainId: null }, issues: ["NAME_CONFLICT"] }],
      }));
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    await user.click(await screen.findByRole("checkbox", { name: "Remote Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    expect(await screen.findAllByText("The source name conflicts with an existing record")).toHaveLength(2);
    expect(screen.getByText("Resolve the preview issues before applying these changes.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply preview" })).toBeDisabled();
  });

  test("shows a preview transport failure inline and allows retry", async () => {
    let attempts = 0;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.includes("registry-candidates")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") {
        attempts += 1;
        return Promise.resolve(attempts === 1 ? jsonResponse(500, { title: "failed", status: 500 }) : jsonResponse(200, { kind: "DOMAIN", cache: CACHE, planToken: "retry", canApply: true, items: [] }));
      }
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    await user.click(await screen.findByRole("checkbox", { name: "Remote Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    expect(await screen.findByText("Action failed (500)")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    expect(await screen.findByRole("region", { name: "Synchronization preview" })).toBeInTheDocument();
  });

  test("rejects a stale apply token and requires a fresh preview", async () => {
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/domains?")) return Promise.resolve(jsonResponse(200, DOMAINS));
      if (url.includes("registry-candidates")) return Promise.resolve(jsonResponse(200, candidatePage(DOMAIN)));
      if (url.endsWith("/registry-sync/preview") && init?.method === "POST") return Promise.resolve(jsonResponse(200, { kind: "DOMAIN", cache: CACHE, planToken: "old", canApply: true, items: [] }));
      if (url.endsWith("/registry-sync") && init?.method === "POST") return Promise.resolve(jsonResponse(409, { title: "Conflict", status: 409 }));
      return Promise.resolve(jsonResponse(404, { title: "missing", status: 404 }));
    });
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySyncModal connection={CONNECTION} onClose={vi.fn()} onConfigure={vi.fn()} onApplied={vi.fn()} />);
    await user.click(await screen.findByRole("checkbox", { name: "Remote Payments" }));
    await user.click(screen.getByRole("button", { name: "Preview changes" }));
    await user.click(await screen.findByRole("button", { name: "Apply preview" }));
    expect(await screen.findByText("The source or local registry changed. Prepare a fresh preview before applying.")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "Synchronization preview" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Preview changes" })).toBeEnabled();
  });
});
