import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import type { ContractResponse } from "../api/contracts";
import TryItDrawer from "./TryItDrawer";
import { bodyOf, CONTRACT, findCall, serve, signIn, VERSION, type FetchMock } from "../test/contractsFixtures";

vi.mock("../components/LazyCodeEditor", async () => (await import("../test/codeEditorStub")).codeEditorMock());

const ENV = { id: 9, systemId: 7, systemName: "gateway", name: "staging", description: null, httpBaseUrl: "http://gw", kafka: null, postgres: null, createdAt: 1, updatedAt: 2 };
const KAFKA_ENV = { ...ENV, id: 10, name: "cluster", httpBaseUrl: null, kafka: { bootstrapServers: "k:9092", securityProtocol: "PLAINTEXT", saslMechanism: null, username: null, hasPassword: false } };
const PG_ENV = { ...ENV, id: 11, name: "warehouse", httpBaseUrl: null, postgres: { jdbcUrl: "jdbc:postgresql://db:5432/app", username: "reader", hasPassword: true } };
const page = (items: unknown[]) => ({ items, page: 1, pageSize: 100, total: items.length });
const HTTP_CATALOG = {
  type: "OPENAPI",
  http: [
    { operationId: "getPet", method: "GET", path: "/pets/{id}", summary: "One pet", parameters: [{ name: "id", location: "path", required: true, type: "integer" }], requestBody: null, responses: ["200"], securityHeaders: ["Authorization"] },
    { operationId: "addPet", method: "POST", path: "/pets", summary: null, parameters: [], requestBody: { required: true, mediaTypes: ["application/json"] }, responses: ["201"], securityHeaders: [] },
  ],
  kafka: [],
  sql: [],
};
const KAFKA_CATALOG = {
  type: "ASYNCAPI",
  http: [],
  kafka: [{ channel: "lightMeasured", address: "lights.measured", actions: ["receive"], messages: [{ name: "lightMeasured", contentType: "application/json", schemaFormat: null, payloadPointer: "/components/messages/lightMeasured/payload" }] }],
  sql: [],
};
const SQL_CATALOG = {
  type: "ODCS",
  http: [],
  kafka: [],
  sql: [
    { name: "customers", physicalName: null, physicalType: "view", properties: [{ name: "id", physicalName: null, logicalType: "integer", physicalType: null, required: true }] },
    { name: "events", physicalName: null, physicalType: "topic", properties: [] },
  ],
};
const FINDING = { severity: "ERROR", source: "CONFORMANCE", code: "RESPONSE_SCHEMA_MISMATCH", message: "$.id: string found, integer expected", path: "/body/id", line: null, column: null };
const report = (findings: unknown[]) => ({ findings, errors: findings.length, warnings: 0, infos: 0, validatedAgainst: null });

function renderDrawer(contract: ContractResponse = CONTRACT) {
  return renderWithProviders(<TryItDrawer contract={contract} version={VERSION} opened onClose={() => {}} />);
}

describe("TryItDrawer", () => {
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

  test("HTTP: pick an operation, bind the path, add a header, Send — the response and its conformance findings", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: HTTP_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([ENV, PG_ENV]) },
      "POST /api/v1/contracts/5/versions/11/try/http": {
        status: 200,
        body: { url: "http://gw/pets/7", status: 200, headers: { "content-type": "application/json", "x-trace": "abc" }, body: '{"id": "x"}', bodyTruncated: false, durationMs: 12, conformance: report([FINDING]) },
      },
    });
    const user = userEvent.setup();
    renderDrawer();
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(await screen.findByLabelText("Environment", { selector: "input" })).toHaveValue("staging");
    await user.click(screen.getByLabelText("Operation", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "GET /pets/{id} — One pet" }));
    expect(screen.getByText("The document expects Authorization. Values are sent once and never stored.")).toBeInTheDocument();
    const send = screen.getByRole("button", { name: "Send" });
    expect(send).toBeDisabled();
    await user.type(screen.getByLabelText(/Path parameter id/), "7");
    expect(send).toBeEnabled();
    const headers = screen.getByRole("group", { name: "Headers" });
    await user.click(within(headers).getByRole("button", { name: "Add" }));
    await user.type(within(headers).getByLabelText("Headers: Name 1"), "Authorization");
    await user.type(within(headers).getByLabelText("Headers: Value 1"), "Bearer x");
    await user.click(send);
    expect(await screen.findByText("Status 200")).toBeInTheDocument();
    const call = findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/try/http");
    expect(bodyOf(call)).toEqual({ environmentId: 9, method: "GET", path: "/pets/{id}", pathParams: { id: "7" }, query: {}, headers: { Authorization: "Bearer x" }, contentType: null, body: null });
    expect(screen.getByText("RESPONSE_SCHEMA_MISMATCH")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "Response body" })).toHaveValue('{"id": "x"}');
    await user.click(screen.getByRole("button", { name: "Response headers (2)" }));
    expect(screen.getByText("x-trace")).toBeInTheDocument();
    expect(localStorage.getItem("covenant.viewSettings.tryIt.environment.7")).toBeNull();
  });

  test("HTTP: a body-bearing operation offers the content type and editor; a 502 shows the server's detail inline", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: HTTP_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([ENV]) },
      "POST /api/v1/contracts/5/versions/11/try/http": { status: 502, body: { title: "Bad Gateway", status: 502, detail: "The environment could not be reached" } },
    });
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByRole("dialog");
    await user.click(await screen.findByLabelText("Operation", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "POST /pets" }));
    expect(screen.getByLabelText("Content type", { selector: "input" })).toHaveValue("application/json");
    await user.type(screen.getByRole("textbox", { name: "Request body" }), '{{"id": 1}');
    await user.click(screen.getByRole("button", { name: "Send" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("The environment could not be reached");
    const call = findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/try/http");
    expect(bodyOf(call)).toMatchObject({ method: "POST", path: "/pets", contentType: "application/json", body: '{"id": 1}' });
  });

  test("no environment with the leg's target: the empty state, with the registry link for an admin", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: HTTP_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([PG_ENV]) },
    });
    renderDrawer();
    expect(await screen.findByText("No environment of this system has a HTTP target.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Add one in the Environments registry" })).toHaveAttribute("href", "/environments");
  });

  test("a document offering nothing, and a failed catalog load", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: { type: "OPENAPI", http: [], kafka: [], sql: [] } },
      "GET /api/v1/environments?": { status: 200, body: page([ENV]) },
    });
    const { unmount } = renderDrawer();
    expect(await screen.findByText("This document offers nothing to try.")).toBeInTheDocument();
    unmount();
    serve(mockFetch, { "GET /api/v1/environments?": { status: 200, body: page([ENV]) } });
    renderDrawer();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (404)");
  });

  test("Kafka: a reader gets Read recent only; the records and their per-message findings render", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: KAFKA_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([KAFKA_ENV, ENV]) },
      "POST /api/v1/contracts/5/versions/11/try/kafka/read": {
        status: 200,
        body: {
          topic: "lights.measured",
          messages: [
            { partition: 0, offset: 4, timestamp: 1_700_000_000_000, key: "lamp-1", headers: {}, payload: '{"lumens": -1}', encoding: "utf8", truncated: false },
            { partition: 0, offset: 3, timestamp: 1_699_999_999_000, key: null, headers: {}, payload: null, encoding: "utf8", truncated: false },
          ],
          reachedEnd: true,
          durationMs: 40,
          conformance: report([{ ...FINDING, code: "PAYLOAD_SCHEMA_MISMATCH", path: "/messages/0/payload/lumens" }]),
        },
      },
    });
    const user = userEvent.setup();
    renderDrawer({ ...CONTRACT, type: "ASYNCAPI", canWrite: false });
    await screen.findByRole("dialog");
    expect(await screen.findByLabelText("Environment", { selector: "input" })).toHaveValue("cluster");
    expect(screen.getByText("Only the contract's writers may publish; reading is open to everyone.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Publish" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Read recent" }));
    expect(await screen.findByText("Records on lights.measured")).toBeInTheDocument();
    expect(screen.getByText("Partition 0, offset 4")).toBeInTheDocument();
    expect(screen.getByText("No payload (a tombstone)")).toBeInTheDocument();
    expect(screen.getByText("PAYLOAD_SCHEMA_MISMATCH")).toBeInTheDocument();
    const call = findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/try/kafka/read");
    expect(bodyOf(call)).toEqual({ environmentId: 10, channel: "lightMeasured", message: "lightMeasured", limit: 10 });
  });

  test("Kafka: a writer publishes a payload and sees where it landed", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: KAFKA_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([KAFKA_ENV]) },
      "POST /api/v1/contracts/5/versions/11/try/kafka/publish": {
        status: 200,
        body: { topic: "lights.measured", partition: 0, offset: 5, timestamp: 1, durationMs: 9, conformance: report([]) },
      },
    });
    const user = userEvent.setup();
    renderDrawer({ ...CONTRACT, type: "ASYNCAPI" });
    await screen.findByRole("dialog");
    const publish = await screen.findByRole("button", { name: "Publish" });
    expect(publish).toBeDisabled();
    await user.type(screen.getByRole("textbox", { name: "Key" }), "lamp-1");
    await user.type(screen.getByRole("textbox", { name: "Payload" }), '{{"lumens": 5}');
    await user.click(publish);
    expect(await screen.findByText("Published to lights.measured — partition 0, offset 5")).toBeInTheDocument();
    expect(screen.getByText("No findings — the document passes every check")).toBeInTheDocument();
    const call = findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/try/kafka/publish");
    expect(bodyOf(call)).toEqual({ environmentId: 10, channel: "lightMeasured", message: "lightMeasured", key: "lamp-1", headers: {}, payload: '{"lumens": 5}' });
  });

  test("SQL: only table-like datasets are offered; Run shows the statement, the rows with NULLs and the column findings", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: SQL_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([PG_ENV]) },
      "POST /api/v1/contracts/5/versions/11/try/sql": {
        status: 200,
        body: {
          statement: 'SELECT * FROM "customers" LIMIT 50',
          columns: [{ name: "id", dbType: "int4", nullable: false, declaredLogicalType: "integer" }, { name: "note", dbType: "text", nullable: true, declaredLogicalType: null }],
          rows: [["1", "hello"], ["2", null]],
          truncated: false,
          durationMs: 5,
          conformance: report([{ ...FINDING, severity: "WARN", code: "COLUMN_EXTRA", path: null }]),
        },
      },
    });
    const user = userEvent.setup();
    renderDrawer({ ...CONTRACT, type: "ODCS" });
    await screen.findByRole("dialog");
    await user.click(await screen.findByLabelText("Dataset", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "customers" })).toBeInTheDocument();
    expect(screen.queryByRole("option", { name: "events" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: "customers" }));
    await user.click(screen.getByRole("button", { name: "Run" }));
    expect(await screen.findByText('SELECT * FROM "customers" LIMIT 50')).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Sample rows" });
    expect(within(table).getByText("hello")).toBeInTheDocument();
    expect(within(table).getByText("NULL")).toBeInTheDocument();
    expect(within(table).getByText("int4")).toBeInTheDocument();
    expect(screen.getByText("COLUMN_EXTRA")).toBeInTheDocument();
    expect(screen.getByText("2 rows · 5 ms")).toBeInTheDocument();
    await waitFor(() => expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts/5/versions/11/try/sql"))).toEqual({ environmentId: 11, dataset: "customers", limit: 50 }));
  });

  test("the environment choice is remembered per system", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5/versions/11/try": { status: 200, body: HTTP_CATALOG },
      "GET /api/v1/environments?": { status: 200, body: page([ENV, { ...ENV, id: 12, name: "prod" }]) },
    });
    const user = userEvent.setup();
    renderDrawer();
    await screen.findByRole("dialog");
    await user.click(await screen.findByLabelText("Environment", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "prod" }));
    expect(await screen.findByLabelText("Environment", { selector: "input" })).toHaveValue("prod");
    expect(JSON.parse(localStorage.getItem("covenant.viewSettings.tryIt.environment.7") ?? "null")).toBe("12");
    expect(screen.getByRole("button", { name: "Close" })).toBeInTheDocument();
  });
});
