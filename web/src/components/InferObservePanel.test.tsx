import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import InferObservePanel from "./InferObservePanel";
import { serve, signIn, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const HTTP_ENV = { id: 1, systemId: 7, systemName: "gateway", httpBaseUrl: "http://127.0.0.1:9999", name: "staging", createdAt: 1, updatedAt: 1 };
const KAFKA_ONLY_ENV = { id: 2, systemId: 7, systemName: "gateway", kafka: { bootstrapServers: ["k:9092"], securityProtocol: "PLAINTEXT" }, name: "events", createdAt: 1, updatedAt: 1 };
const OTHER_SYSTEM_HTTP_ENV = { id: 3, systemId: 9, systemName: "billing", httpBaseUrl: "http://127.0.0.1:9998", name: "prod", createdAt: 1, updatedAt: 1 };
const PG_ENV = { id: 4, systemId: 7, systemName: "gateway", postgres: { jdbcUrl: "jdbc:postgresql://db/covenant" }, name: "data", createdAt: 1, updatedAt: 1 };

describe("InferObservePanel", () => {
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

  test("only environments carrying the leg's target are offered, labelled System / Environment when the contract has none yet", async () => {
    serve(mockFetch, { "GET /api/v1/environments?": { status: 200, body: { items: [HTTP_ENV, KAFKA_ONLY_ENV, OTHER_SYSTEM_HTTP_ENV], page: 1, pageSize: 100, total: 3 } } });
    renderWithProviders(<InferObservePanel type="OPENAPI" systemId={null} onAdd={vi.fn()} />);
    const select = await screen.findByRole("combobox", { name: "Environment" });
    await userEvent.setup().click(select);
    expect(await screen.findByRole("option", { name: "gateway / staging" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "billing / prod" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: /events/ })).not.toBeInTheDocument();
  });

  test("the relations Select is populated from the stub; Describe adds the result to samples", async () => {
    serve(mockFetch, {
      "GET /api/v1/environments?": { status: 200, body: { items: [PG_ENV], page: 1, pageSize: 100, total: 1 } },
      "POST /api/v1/contracts/infer/observe/sql/relations": { status: 200, body: { relations: [{ schema: "public", name: "users", kind: "table" }] } },
      "POST /api/v1/contracts/infer/observe/sql": { status: 200, body: { sample: { name: "public.users", physicalType: "table", columns: [{ name: "id", dbType: "int4", nullable: false }] }, notes: [] } },
    });
    const user = userEvent.setup();
    const onAdd = vi.fn();
    renderWithProviders(<InferObservePanel type="ODCS" systemId={7} onAdd={onAdd} />);
    await screen.findByRole("combobox", { name: "Environment" });
    const relationSelect = await screen.findByRole("combobox", { name: "Dataset" });
    await user.click(relationSelect);
    await user.click(await screen.findByRole("option", { name: "public.users (table)" }));
    await user.click(screen.getByRole("button", { name: "Describe" }));
    expect(await screen.findByText("1 column described")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add to samples" }));
    expect(onAdd).toHaveBeenCalledWith({ name: "public.users", physicalType: "table", columns: [{ name: "id", dbType: "int4", nullable: false }] });
  });

  test("a 502 from the environment call is rendered verbatim; the method and content type are editable", async () => {
    serve(mockFetch, {
      "GET /api/v1/environments?": { status: 200, body: { items: [HTTP_ENV], page: 1, pageSize: 100, total: 1 } },
      "POST /api/v1/contracts/infer/observe/http": { status: 502, body: { title: "Bad Gateway", status: 502, detail: "The environment could not be reached" } },
    });
    const user = userEvent.setup();
    renderWithProviders(<InferObservePanel type="OPENAPI" systemId={7} onAdd={vi.fn()} />);
    await user.click(await screen.findByRole("combobox", { name: "Method" }));
    await user.click(await screen.findByRole("option", { name: "POST" }));
    await user.clear(screen.getByLabelText("Content type"));
    await user.type(screen.getByLabelText("Content type"), "application/vnd.acme+json");
    await user.type(screen.getByLabelText("Path"), "/orders/42");
    await user.click(screen.getByRole("button", { name: "Send" }));
    await waitFor(() => expect(screen.getByText("The environment could not be reached")).toBeInTheDocument());
  });

  test("the Kafka leg reads a topic and adds the batch to samples", async () => {
    serve(mockFetch, {
      "GET /api/v1/environments?": { status: 200, body: { items: [KAFKA_ONLY_ENV], page: 1, pageSize: 100, total: 1 } },
      "POST /api/v1/contracts/infer/observe/kafka": { status: 200, body: { sample: { channel: "orders.v1.created", payloads: ['{"a":1}'] }, notes: [], reachedEnd: true } },
    });
    const user = userEvent.setup();
    const onAdd = vi.fn();
    renderWithProviders(<InferObservePanel type="ASYNCAPI" systemId={7} onAdd={onAdd} />);
    await user.type(await screen.findByLabelText("Channel"), "orders.v1.created");
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByText("1 record read")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add to samples" }));
    expect(onAdd).toHaveBeenCalledWith({ channel: "orders.v1.created", payloads: ['{"a":1}'] });
  });

  test("picking a different environment persists the choice", async () => {
    serve(mockFetch, { "GET /api/v1/environments?": { status: 200, body: { items: [HTTP_ENV, OTHER_SYSTEM_HTTP_ENV], page: 1, pageSize: 100, total: 2 } } });
    const user = userEvent.setup();
    renderWithProviders(<InferObservePanel type="OPENAPI" systemId={null} onAdd={vi.fn()} />);
    const select = await screen.findByRole("combobox", { name: "Environment" });
    await user.click(select);
    await user.click(await screen.findByRole("option", { name: "billing / prod" }));
    await waitFor(() => expect(select).toHaveValue("billing / prod"));
  });
});
