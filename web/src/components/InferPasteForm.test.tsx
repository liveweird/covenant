import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, renderWithProviders, screen } from "../test/render";
import InferPasteForm from "./InferPasteForm";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

describe("InferPasteForm", () => {
  test("OpenAPI: an exchange is added with the typed method/url/status", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="OPENAPI" onAdd={onAdd} />);
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders/42");
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({
      method: "GET",
      url: "https://api.example.test/orders/42",
      status: 200,
      requestContentType: null,
      requestBody: null,
      responseContentType: null,
      responseBody: null,
    });
  });

  test("OpenAPI: the method, content types and bodies are all editable", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="OPENAPI" onAdd={onAdd} />);
    await user.click(screen.getByRole("combobox", { name: "Method" }));
    await user.click(await screen.findByRole("option", { name: "POST" }));
    await user.type(screen.getByLabelText("URL"), "https://api.example.test/orders");
    await user.clear(screen.getByLabelText("Request content type"));
    await user.type(screen.getByLabelText("Request content type"), "application/vnd.acme+json");
    fireEvent.change(screen.getByRole("textbox", { name: "Request body" }), { target: { value: '{"name":"widget"}' } });
    await user.clear(screen.getByLabelText("Response content type"));
    await user.type(screen.getByLabelText("Response content type"), "application/vnd.acme+json");
    fireEvent.change(screen.getByRole("textbox", { name: "Response body" }), { target: { value: '{"id":1}' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({
      method: "POST",
      url: "https://api.example.test/orders",
      status: 200,
      requestContentType: "application/vnd.acme+json",
      requestBody: '{"name":"widget"}',
      responseContentType: "application/vnd.acme+json",
      responseBody: '{"id":1}',
    });
  });

  test("AsyncAPI: a JSON array of payloads becomes payloads[]", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={onAdd} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: '[{"a":1},{"a":2}]' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({ channel: "orders.v1.created", payloads: ['{"a":1}', '{"a":2}'] });
  });

  test("AsyncAPI: an empty payload array is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: "[]" } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("Add at least one payload.")).toBeInTheDocument();
  });

  test("AsyncAPI: an invalid payload line is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: "not json" } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("Each payload must be valid JSON.")).toBeInTheDocument();
  });

  test("AsyncAPI: a single bare object on one line becomes a one-element payloads[]", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={onAdd} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: '{"a":1}' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({ channel: "orders.v1.created", payloads: ['{"a":1}'] });
  });

  test("AsyncAPI: blank lines between valid lines are ignored", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={onAdd} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: '{"a":1}\n\n\n{"a":2}\n' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({ channel: "orders.v1.created", payloads: ['{"a":1}', '{"a":2}'] });
  });

  test("AsyncAPI: a trailing comma is refused as invalid JSON", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ASYNCAPI" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Channel"), "orders.v1.created");
    fireEvent.change(screen.getByRole("textbox", { name: "Payloads" }), { target: { value: '{"a":1},' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("Each payload must be valid JSON.")).toBeInTheDocument();
  });

  test("ODCS: a JSON array of rows is added", async () => {
    const onAdd = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ODCS" onAdd={onAdd} />);
    await user.type(screen.getByLabelText("Relation"), "public.users");
    fireEvent.change(screen.getByRole("textbox", { name: "Rows" }), { target: { value: '[{"id":1},{"id":2}]' } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(onAdd).toHaveBeenCalledWith({ name: "public.users", rows: ['{"id":1}', '{"id":2}'] });
  });

  test("ODCS: a non-array is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ODCS" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Relation"), "public.users");
    fireEvent.change(screen.getByRole("textbox", { name: "Rows" }), { target: { value: "{}" } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("The rows must be a JSON array of objects.")).toBeInTheDocument();
  });

  test("ODCS: text that is not JSON at all is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ODCS" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Relation"), "public.users");
    fireEvent.change(screen.getByRole("textbox", { name: "Rows" }), { target: { value: "not json" } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("The rows must be a JSON array of objects.")).toBeInTheDocument();
  });

  test("ODCS: an empty row array is refused", async () => {
    const user = userEvent.setup();
    renderWithProviders(<InferPasteForm type="ODCS" onAdd={vi.fn()} />);
    await user.type(screen.getByLabelText("Relation"), "public.users");
    fireEvent.change(screen.getByRole("textbox", { name: "Rows" }), { target: { value: "[]" } });
    await user.click(screen.getByRole("button", { name: "Add sample" }));
    expect(await screen.findByText("Add at least one row.")).toBeInTheDocument();
  });
});
