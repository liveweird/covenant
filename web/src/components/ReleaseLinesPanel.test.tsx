import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import { bodyOf, findCall, serve, signIn, VERSION_PAGE, type FetchMock } from "../test/contractsFixtures";
import ReleaseLinesPanel from "./ReleaseLinesPanel";

const LINE = {
  id: 21,
  contractId: 5,
  major: 1,
  supportStatus: "SUPPORTED" as const,
  supportEndsOn: "2027-12-31",
  supportPolicy: "Security fixes and critical bug fixes.",
  latestVersion: { id: 11, version: "1.1.0", lifecycle: "DRAFT" as const },
  recommendedVersionId: 10,
  recommendedVersion: { id: 10, version: "1.0.0", lifecycle: "ACTIVE" as const },
  versionCount: 2,
  deprecatesOn: null,
  replacement: null,
  migrationGuide: null,
  updatedAt: 2,
};
const PAGE = { items: [LINE], page: 1, pageSize: 100, total: 1 };

describe("ReleaseLinesPanel", () => {
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

  test("shows an honest line summary and writer actions, and filters through its callback", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5/release-lines?": { status: 200, body: PAGE } });
    const onFilter = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite onFilter={onFilter} />);
    await screen.findByRole("region", { name: "Release lines" });
    const card = await screen.findByRole("region", { name: "Release line 1.x" });
    expect(within(card).getByText("Supported")).toBeInTheDocument();
    const recommended = within(card).getByRole("link", { name: "1.0.0" });
    expect(recommended).toHaveAttribute("href", "/contracts/5/versions/10");
    expect(recommended).toHaveClass("mantine-Anchor-root");
    expect(within(card).getByRole("link", { name: "New version in 1.x" })).toHaveAttribute("href", "/contracts/5/versions/new?from=11&major=1");
    await user.click(within(card).getByRole("button", { name: "Filter versions in 1.x" }));
    expect(onFilter).toHaveBeenCalledWith(1);
  });

  test("a reader can inspect and filter a line but cannot mutate it", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5/release-lines?": { status: 200, body: PAGE } });
    renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite={false} onFilter={() => {}} />);
    const card = await screen.findByRole("region", { name: "Release line 1.x" });
    expect(within(card).getByRole("button", { name: "Filter versions in 1.x" })).toBeInTheDocument();
    expect(within(card).queryByRole("link", { name: "New version in 1.x" })).not.toBeInTheDocument();
    expect(within(card).queryByRole("button", { name: "Edit policy for 1.x" })).not.toBeInTheDocument();
  });

  test("distinguishes loading, empty and failed states", async () => {
    mockFetch.mockReturnValue(new Promise(() => {}));
    const loading = renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite onFilter={() => {}} />);
    expect(screen.getByLabelText("Loading…")).toBeInTheDocument();
    loading.unmount();

    serve(mockFetch, { "GET /api/v1/contracts/5/release-lines?": { status: 200, body: { ...PAGE, items: [], total: 0 } } });
    const empty = renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite onFilter={() => {}} />);
    expect(await screen.findByText("No release lines yet")).toBeInTheDocument();
    empty.unmount();

    serve(mockFetch, { "GET /api/v1/contracts/5/release-lines?": { status: 500, body: { title: "nope" } } });
    renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite onFilter={() => {}} />);
    expect(await screen.findByText("Could not load the release lines")).toBeInTheDocument();
  });

  test("saves every policy field and clears the recommendation at end of life", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/release-lines?": { status: 200, body: PAGE },
      "GET /api/v1/contracts/5/versions?": { status: 200, body: VERSION_PAGE },
      "PUT /api/v1/contracts/5/release-lines/1": { status: 204 },
    });
    const user = userEvent.setup();
    renderWithProviders(<ReleaseLinesPanel contractId={5} canWrite onFilter={() => {}} />);
    await user.click(await screen.findByRole("button", { name: "Edit policy for 1.x" }));
    const dialog = screen.getByRole("dialog", { name: "Edit policy for 1.x" });
    await user.click(within(dialog).getByRole("combobox", { name: "Support status" }));
    await user.click(screen.getByRole("option", { name: "End of life" }));
    expect(within(dialog).getByRole("combobox", { name: "Recommended version" })).toBeDisabled();
    await user.click(within(dialog).getByRole("button", { name: "Save policy" }));
    const impact = await screen.findByRole("dialog", { name: "Retirement impact for 1.x" });
    expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/release-lines/1")).toBeUndefined();
    await user.click(within(impact).getByRole("checkbox"));
    await waitFor(() => expect(within(impact).getByRole("button", { name: "End support" })).toBeEnabled());
    await user.click(within(impact).getByRole("button", { name: "End support" }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/release-lines/1")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5/release-lines/1"))).toEqual({
      deprecatesOn: null,
      replacementContractId: null,
      replacementMajor: null,
      migrationGuide: null,
      supportStatus: "END_OF_LIFE",
      supportEndsOn: "2027-12-31",
      supportPolicy: "Security fixes and critical bug fixes.",
      recommendedVersionId: null,
    });
  });
});
