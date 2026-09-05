import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import DocumentSourcePicker from "./DocumentSourcePicker";
import { bodyOf, findCall, serve, signIn, type FetchMock } from "../test/contractsFixtures";

describe("DocumentSourcePicker", () => {
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

  test("fetches a normalized URL server-side and hands the text up with its origin", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/fetch": { status: 200, body: { content: "openapi: 3.1.0" } } });
    const onLoad = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<DocumentSourcePicker onLoad={onLoad} />);
    const fetchButton = screen.getByRole("button", { name: "Fetch" });
    expect(fetchButton).toBeDisabled();
    await user.type(screen.getByLabelText("Fetch from a URL"), "https://github.com/acme/orders/blob/main/openapi.yaml");
    await user.click(fetchButton);
    await waitFor(() => expect(onLoad).toHaveBeenCalledWith("openapi: 3.1.0", "https://raw.githubusercontent.com/acme/orders/main/openapi.yaml"));
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/fetch"))).toEqual({ url: "https://raw.githubusercontent.com/acme/orders/main/openapi.yaml" });
    expect(screen.getByText(/Fetched from https:\/\/raw.githubusercontent.com/)).toBeInTheDocument();
  });

  test("a refused URL renders the fixed vocabulary; a 502 the status form", async () => {
    serve(mockFetch, { "POST /api/v1/contracts/fetch": { status: 400, body: { title: "Bad Request", status: 400 } } });
    const user = userEvent.setup();
    renderWithProviders(<DocumentSourcePicker onLoad={vi.fn()} />);
    await user.type(screen.getByLabelText("Fetch from a URL"), "https://10.0.0.1/x");
    await user.click(screen.getByRole("button", { name: "Fetch" }));
    expect(await screen.findByText("Only public https:// URLs can be fetched")).toBeInTheDocument();
    serve(mockFetch, { "POST /api/v1/contracts/fetch": { status: 502, body: { title: "Bad Gateway", status: 502 } } });
    await user.click(screen.getByRole("button", { name: "Fetch" }));
    expect(await screen.findByText("Fetch failed (502)")).toBeInTheDocument();
  });

  test("a picked file is read and handed up with its name", async () => {
    const onLoad = vi.fn();
    renderWithProviders(<DocumentSourcePicker onLoad={onLoad} />);
    const input = document.querySelector<HTMLInputElement>('input[type="file"]')!;
    const file = new File(["asyncapi: 3.0.0"], "events.yaml", { type: "text/yaml" });
    fireEvent.change(input, { target: { files: [file] } });
    await waitFor(() => expect(onLoad).toHaveBeenCalledWith("asyncapi: 3.0.0", "events.yaml"));
  });
});
