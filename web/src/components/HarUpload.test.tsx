import { afterEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, renderWithProviders, screen, waitFor } from "../test/render";
import HarUpload from "./HarUpload";

function entry(request: Record<string, unknown>, response: Record<string, unknown>) {
  return {
    request: { httpVersion: "HTTP/1.1", cookies: [], headersSize: -1, bodySize: -1, ...request },
    response: { httpVersion: "HTTP/1.1", cookies: [], headersSize: -1, bodySize: -1, redirectURL: "", ...response },
  };
}

const HAR = {
  log: {
    version: "1.2",
    creator: { name: "test", version: "1.0" },
    entries: [
      entry(
        { method: "GET", url: "https://api.example.test/orders/42", headers: [], queryString: [] },
        { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: '{"id":42}' } },
      ),
      entry(
        { method: "GET", url: "https://api.example.test/orders/43", headers: [], queryString: [] },
        { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: '{"id":43}' } },
      ),
      entry({ method: "OPTIONS", url: "https://api.example.test/orders/42", headers: [], queryString: [] }, { status: 204, headers: [], content: {} }),
    ],
  },
};

function fileInput(): HTMLInputElement {
  return document.querySelector<HTMLInputElement>('input[type="file"]')!;
}

describe("HarUpload", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  test("reads a HAR file, defaults the origin and adds up to 50 exchanges of it, reporting kept vs total", async () => {
    const onAdd = vi.fn();
    renderWithProviders(<HarUpload onAdd={onAdd} />);
    const file = new File([JSON.stringify(HAR)], "export.har", { type: "application/json" });
    fireEvent.change(fileInput(), { target: { files: [file] } });
    expect(await screen.findByRole("combobox", { name: "Origin" })).toHaveValue("https://api.example.test (2)");
    await userEvent.setup().click(screen.getByRole("button", { name: "Add" }));
    expect(onAdd).toHaveBeenCalledTimes(1);
    expect(onAdd.mock.calls[0][0]).toHaveLength(2);
    expect(await screen.findByText("Kept 2 of 3 entries")).toBeInTheDocument();
  });

  test("a file that is not a HAR export renders the fixed vocabulary", async () => {
    renderWithProviders(<HarUpload onAdd={vi.fn()} />);
    const file = new File([JSON.stringify({ not: "a har file" })], "export.har", { type: "application/json" });
    fireEvent.change(fileInput(), { target: { files: [file] } });
    await waitFor(() => expect(screen.getByText("That file is not a HAR export.")).toBeInTheDocument());
    expect(screen.queryByRole("combobox", { name: "Origin" })).not.toBeInTheDocument();
  });
});
