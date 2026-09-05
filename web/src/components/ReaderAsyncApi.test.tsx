import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import { MODEL_ASYNCAPI } from "../test/readerFixtures";
import ReaderAsyncApi from "./ReaderAsyncApi";

const FULL = MODEL_ASYNCAPI.asyncApi!;

/** The empty states — the rich fixture's presence branches are asserted through ContractReader.test. */
describe("ReaderAsyncApi", () => {
  test("a document with no servers, channels, operations or messages says so instead of rendering empty sections", () => {
    const bare = { ...FULL, servers: [], channels: [], operations: [], messages: [], schemas: [], securitySchemes: [] };
    renderWithProviders(<ReaderAsyncApi model={bare} specVersion="3.0.0" />);
    expect(screen.getByText("No channels declared")).toBeInTheDocument();
    expect(screen.getByText("No operations declared")).toBeInTheDocument();
    expect(screen.getByText("No messages declared")).toBeInTheDocument();
    for (const title of ["Servers", "Schemas", "Security"]) {
      expect(screen.queryByRole("heading", { name: title })).not.toBeInTheDocument();
    }
  });

  test("a 3.x document shows the verb without a dimmed legacy verb, and a deprecated inline message carries its badges", () => {
    renderWithProviders(<ReaderAsyncApi model={FULL} specVersion="3.0.0" />);
    const dim = screen.getByRole("article", { name: "dim" });
    expect(dim).toHaveTextContent("send");
    expect(dim).not.toHaveTextContent("(publish)");
    const onMeasured = screen.getByRole("article", { name: "onMeasured" });
    expect(onMeasured).toHaveTextContent("(publish)");
    const raw = screen.getByRole("article", { name: "proto/raw" });
    expect(raw).toHaveTextContent("Deprecated");
    expect(raw).toHaveTextContent("message Raw {}");
  });
});
