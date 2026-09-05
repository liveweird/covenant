import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import SourceUrlModal from "./SourceUrlModal";
import { bodyOf, findCall, serve, signIn, VERSION, type FetchMock } from "../test/contractsFixtures";

describe("SourceUrlModal", () => {
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

  test("links an https URL, refuses anything else client-side, and unlinks with an empty field", async () => {
    serve(mockFetch, { "PUT /api/v1/contracts/5/versions/11/source": { status: 204 } });
    const onClose = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<SourceUrlModal contractId={5} version={VERSION} onClose={onClose} />);
    const field = screen.getByLabelText("Repository URL");
    await user.type(field, "http://example.com/x.yaml");
    expect(screen.getByText("Only https:// URLs without credentials can be linked")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save link" })).toBeDisabled();
    await user.clear(field);
    await user.type(field, "https://gitlab.com/acme/c/-/raw/main/p.yaml");
    await user.click(screen.getByRole("button", { name: "Save link" }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/source")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/source"))).toEqual({ sourceUrl: "https://gitlab.com/acme/c/-/raw/main/p.yaml" });
    await waitFor(() => expect(onClose).toHaveBeenCalled());
  });

  test("an empty field unlinks; a 400 from the server renders the rule", async () => {
    serve(mockFetch, { "PUT /api/v1/contracts/5/versions/11/source": { status: 400, body: { detail: "sourceUrl must be…" } } });
    const user = userEvent.setup();
    renderWithProviders(<SourceUrlModal contractId={5} version={{ ...VERSION, sourceUrl: "https://x.example/a" }} onClose={vi.fn()} />);
    await user.clear(screen.getByLabelText("Repository URL"));
    await user.click(screen.getByRole("button", { name: "Save link" }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/source")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5/versions/11/source"))).toEqual({ sourceUrl: null });
    expect(await screen.findByRole("alert")).toHaveTextContent("Only https:// URLs without credentials can be linked");
  });
});
