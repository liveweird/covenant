import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { StrictMode } from "react";
import userEvent from "@testing-library/user-event";
import { fireEvent } from "@testing-library/react";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { jsonResponse } from "../test/http";
import RetirementImpactModal from "./RetirementImpactModal";
import LifecycleActions from "./LifecycleActions";
import ReleaseLinePolicyModal from "./ReleaseLinePolicyModal";
import type { ReleaseLineResponse } from "../api/releaseLines";
import { setToken } from "../api/session";

const NativeURL = URL;

const PLAN: ReleaseLineResponse = {
  id: 21, contractId: 5, major: 1, supportStatus: "MAINTENANCE", supportEndsOn: "2027-12-31", supportPolicy: null,
  deprecatesOn: "2027-06-01", migrationGuide: "Use the new endpoint.\nThen remove the old client.",
  replacement: { contractId: 9, contractName: "New orders", major: 2, available: true },
  recommendedVersionId: null, latestVersion: null, recommendedVersion: null, versionCount: 1, updatedAt: 1,
};
const CACHE = { state: "STALE", lastSuccessAt: 10, lastAttemptAt: 20, lastErrorCode: "UPSTREAM_UNAVAILABLE", refreshing: false };
const LINKS = { contractId: 5, connection: { id: 2, name: "Toadie" }, cache: CACHE, items: [{ id: 1, title: "Orders", status: "MISSING" }] };
const USAGE = { page: 1, pageSize: 20, total: 1, cache: CACHE, items: [{ id: "2", identifier: "storefront", title: "Storefront", url: null,
  roles: ["CONSUMER"], systems: [], teams: [{ entityId: "7", title: "Retail", url: null }], version: null, releaseLine: null }] };
const REPORT = { generatedAt: 100, contractId: 5, contractName: "Orders", contractType: "OPENAPI", major: 1,
  supportStatus: "MAINTENANCE", deprecatesOn: "2027-06-01", supportEndsOn: "2027-12-31", supportPolicy: null,
  migrationGuide: "Saved guide", replacement: PLAN.replacement, recommendedVersion: null, planUpdatedAt: 99,
  usageScope: "CONTRACT", versionAdoption: "UNKNOWN", connection: null, cache: CACHE, linkedApis: [], services: [], adoptions: { availability: "NOT_CONFIGURED", items: [] } };

describe("retirement impact", () => {
  let fetchMock: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    localStorage.setItem("covenant.auth.token", "token");
    fetchMock = vi.fn((url: string, init?: RequestInit) => {
      if (init?.method === "PUT") return Promise.resolve(new Response(null, { status: 204 }));
      if (url === "/api/v1/contracts/5/release-lines/1") return Promise.resolve(jsonResponse(200, PLAN));
      if (url === "/api/v1/contracts/5/release-lines/1/migration-report") return Promise.resolve(jsonResponse(200, REPORT));
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, LINKS));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      if (url.startsWith("/api/v1/contracts/5/toadie-adoptions?")) return Promise.resolve(jsonResponse(200, { ...USAGE, items: [], total: 0, availability: "NOT_CONFIGURED" }));
      if (url.startsWith("/api/v1/contracts?")) return Promise.resolve(jsonResponse(200, { items: [], total: 0 }));
      if (url.includes("/versions?") || url.includes("/release-lines?")) return Promise.resolve(jsonResponse(200, { items: [], total: 0 }));
      return Promise.resolve(jsonResponse(404, { title: "Not found", status: 404 }));
    });
    vi.stubGlobal("fetch", fetchMock);
  });
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); });

  test("a reader sees the plan, consumer teams and stale/missing caveats without mutation controls", async () => {
    const close = vi.fn();
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} canWrite={false} onClose={close} />);
    expect(await screen.findByText("Storefront")).toBeInTheDocument();
    expect(await screen.findByText("Planned deprecation: 2027-06-01")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "New orders · 2.x" })).toHaveAttribute("href", "/contracts/9");
    expect(screen.getByText("Retail")).toBeInTheDocument();
    expect(screen.getByText(/whole contract.*runtime adoption remains unknown/)).toBeInTheDocument();
    expect(screen.getByText(/Usage information is incomplete/)).toBeInTheDocument();
    expect(screen.getAllByText("Unknown")).toHaveLength(2);
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox", { name: "Role" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Toadie links" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download report" })).toBeEnabled();
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes("role=CONSUMER"))).toBe(true);
    await userEvent.setup().click(screen.getAllByRole("button", { name: "Close" }).at(-1)!);
    expect(close).toHaveBeenCalled();
  });

  test("a failed report download shows an inline error and retries without invoking the retirement action", async () => {
    let reportAttempts = 0;
    fetchMock.mockImplementation((url: string) => {
      if (url === "/api/v1/contracts/5/release-lines/1/migration-report") {
        reportAttempts += 1;
        return Promise.resolve(reportAttempts === 1 ? jsonResponse(503, { status: 503 }) : jsonResponse(200, REPORT));
      }
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, LINKS));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      return Promise.resolve(jsonResponse(404, { status: 404 }));
    });
    const transition = vi.fn();
    const createObjectURL = vi.fn(() => "blob:report");
    class TestURL extends NativeURL {
      static readonly createObjectURL = createObjectURL;
      static readonly revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", TestURL);
    const user = userEvent.setup();
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} plan={PLAN} canWrite={false} onClose={vi.fn()} onConfirm={transition} confirmLabel="Retire" />);
    await user.click(screen.getByRole("button", { name: "Download report" }));
    expect(await screen.findByText("Could not download the migration report. Try again.")).toBeInTheDocument();
    expect(transition).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Download report" }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledOnce());
    expect(reportAttempts).toBe(2);
    expect(transition).not.toHaveBeenCalled();
  });

  test("a size-limit conflict is actionable and never creates a partial download", async () => {
    fetchMock.mockImplementation((url: string) => {
      if (url === "/api/v1/contracts/5/release-lines/1/migration-report") return Promise.resolve(jsonResponse(409, { status: 409 }));
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, LINKS));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      return Promise.resolve(jsonResponse(404, { status: 404 }));
    });
    const createObjectURL = vi.fn(() => "blob:report");
    class TestURL extends NativeURL {
      static readonly createObjectURL = createObjectURL;
      static readonly revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", TestURL);
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} plan={PLAN} canWrite={false} onClose={vi.fn()} />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Download report" }));
    expect(await screen.findByText(/exceeds the 8 MiB export limit/)).toBeInTheDocument();
    expect(createObjectURL).not.toHaveBeenCalled();
  });

  test("downloads successfully under React Strict Mode", async () => {
    const createObjectURL = vi.fn(() => "blob:report");
    class TestURL extends NativeURL {
      static readonly createObjectURL = createObjectURL;
      static readonly revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", TestURL);
    renderWithProviders(<StrictMode><RetirementImpactModal contractId={5} major={1} plan={PLAN} canWrite={false} onClose={vi.fn()} /></StrictMode>);
    await userEvent.setup().click(screen.getByRole("button", { name: "Download report" }));
    await waitFor(() => expect(createObjectURL).toHaveBeenCalledOnce());
  });

  test("discards a delayed report after the signed-in session is replaced", async () => {
    let resolveReport!: (response: Response) => void;
    fetchMock.mockImplementation((url: string) => {
      if (url === "/api/v1/contracts/5/release-lines/1/migration-report") return new Promise<Response>((resolve) => { resolveReport = resolve; });
      if (url === "/api/v1/contracts/5/toadie-links") return Promise.resolve(jsonResponse(200, LINKS));
      if (url.startsWith("/api/v1/contracts/5/toadie-usage?")) return Promise.resolve(jsonResponse(200, USAGE));
      return Promise.resolve(jsonResponse(404, { status: 404 }));
    });
    const createObjectURL = vi.fn(() => "blob:report");
    class TestURL extends NativeURL {
      static readonly createObjectURL = createObjectURL;
      static readonly revokeObjectURL = vi.fn();
    }
    vi.stubGlobal("URL", TestURL);
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} plan={PLAN} canWrite={false} onClose={vi.fn()} />);
    await userEvent.setup().click(screen.getByRole("button", { name: "Download report" }));
    setToken("another-user-token");
    resolveReport(jsonResponse(200, REPORT));
    await waitFor(() => expect(screen.getByRole("button", { name: "Download report" })).toBeEnabled());
    expect(createObjectURL).not.toHaveBeenCalled();
  });

  test("retirement remains an explicit decision after a failed usage read; cancel never transitions", async () => {
    fetchMock.mockImplementation((url: string) => Promise.resolve(jsonResponse(url.includes("/release-lines/1") ? 200 : 503,
      url.includes("/release-lines/1") ? PLAN : { title: "Unavailable", status: 503 })));
    const transition = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<LifecycleActions contractId={5} lifecycle="DEPRECATED" version="1.2.0" onTransition={transition} />);
    await user.click(screen.getByRole("button", { name: "Retire" }));
    let modal = await screen.findByRole("dialog", { name: "Retirement impact for 1.x" });
    expect(within(modal).getByRole("button", { name: "Retire" })).toBeDisabled();
    await user.click(within(modal).getByRole("button", { name: "Cancel" }));
    expect(transition).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Retire" }));
    modal = await screen.findByRole("dialog");
    await within(modal).findByText("Could not load Toadie usage");
    await user.click(within(modal).getByRole("checkbox"));
    await waitFor(() => expect(within(modal).getByRole("button", { name: "Retire" })).toBeEnabled());
    await user.click(within(modal).getByRole("button", { name: "Retire" }));
    expect(transition).toHaveBeenCalledExactlyOnceWith("RETIRED");
  });

  test("saving guidance preserves an unavailable replacement and all existing policy fields", async () => {
    const close = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ReleaseLinePolicyModal contractId={5} line={{ ...PLAN, replacement: { ...PLAN.replacement!, contractName: null, available: false } }} onClose={close} />);
    expect(screen.getByRole("combobox", { name: "Replacement contract" })).toHaveValue("Unavailable contract #9");
    await user.clear(screen.getByRole("textbox", { name: "Migration instructions" }));
    await user.type(screen.getByRole("textbox", { name: "Migration instructions" }), "  New migration steps  ");
    await user.click(screen.getByRole("button", { name: "Save policy" }));
    await waitFor(() => expect(close).toHaveBeenCalled());
    const request = fetchMock.mock.calls.find(([, init]) => init?.method === "PUT")?.[1];
    expect(JSON.parse(request!.body as string)).toEqual({ supportStatus: "MAINTENANCE", supportEndsOn: "2027-12-31", supportPolicy: null,
      recommendedVersionId: null, deprecatesOn: "2027-06-01", replacementContractId: 9, replacementMajor: 2, migrationGuide: "New migration steps" });
  });

  test("a pending observation cannot be confirmed even after acknowledgment", async () => {
    fetchMock.mockReturnValue(new Promise(() => {}));
    const confirm = vi.fn();
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} plan={PLAN} canWrite onClose={vi.fn()} onConfirm={confirm} confirmLabel="Retire" />);
    await userEvent.setup().click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "Retire" })).toBeDisabled();
    expect(confirm).not.toHaveBeenCalled();
  });
  test("a failed plan load blocks retirement until the plan can be reviewed", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(503, { title: "Unavailable", status: 503 })));
    renderWithProviders(<RetirementImpactModal contractId={5} major={1} canWrite onClose={vi.fn()} onConfirm={vi.fn()} confirmLabel="Retire" />);
    await screen.findByRole("button", { name: "Reload plan" });
    await userEvent.setup().click(screen.getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "Retire" })).toBeDisabled();
    fetchMock.mockImplementation((url: string) => Promise.resolve(jsonResponse(url.includes("/release-lines/1") ? 200 : 503,
      url.includes("/release-lines/1") ? PLAN : { title: "Unavailable", status: 503 })));
    await userEvent.setup().click(screen.getByRole("button", { name: "Reload plan" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Retire" })).toBeEnabled());
  });

  test("self replacement needs another major and deprecation cannot follow support end", async () => {
    const user = userEvent.setup();
    const view = renderWithProviders(<ReleaseLinePolicyModal contractId={5} line={{ ...PLAN, replacement: { contractId: 5, contractName: "Self", major: null, available: true } }} onClose={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Save policy" })).toBeDisabled();
    expect(screen.getByRole("alert")).toHaveTextContent("For this contract, choose a different release line.");
    view.unmount();
    renderWithProviders(<ReleaseLinePolicyModal contractId={5} line={PLAN} onClose={vi.fn()} />);
    const date = screen.getByLabelText("Deprecates on");
    // Native date fields accept canonical input through a change event in the DOM harness.
    fireEvent.change(date, { target: { value: "2028-01-01" } });
    expect(screen.getByRole("button", { name: "Save policy" })).toBeDisabled();
    expect(screen.getByText("Deprecation must be on or before the support-end date.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
  });

});
