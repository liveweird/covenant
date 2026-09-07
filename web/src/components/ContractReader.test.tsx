import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor, within } from "../test/render";
import i18n from "../i18n";
import { readerToc } from "../utils/readerToc";
import ContractReader from "./ContractReader";
import { CONTRACT, serve, signIn, VERSION, type FetchMock } from "../test/contractsFixtures";
import { MODEL_ASYNCAPI, MODEL_EMPTY, MODEL_ERROR, MODEL_ODCS, MODEL_OPENAPI } from "../test/readerFixtures";

const t = i18n.t;
const MODEL_URL = "GET /api/v1/contracts/5/versions/11/model";

describe("ContractReader", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
  });
  afterEach(() => vi.unstubAllGlobals());

  function render(highlight?: { path: string; nonce: number } | null) {
    return renderWithProviders(<ContractReader contract={CONTRACT} version={VERSION} highlight={highlight} />);
  }

  test("OpenAPI: info, servers, operations by tag, schemas, security — with the 3.0 label", async () => {
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_OPENAPI } });
    const user = userEvent.setup();
    render();
    expect(await screen.findByRole("heading", { level: 3, name: "Pets" })).toBeInTheDocument();
    expect(screen.getByText("v2.1.0")).toBeInTheDocument();
    expect(screen.getByText("OpenAPI 3.0.3")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "api@example.com" })).toHaveAttribute("href", "mailto:api@example.com");
    expect(screen.getByRole("link", { name: "MIT" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "The handbook" })).toBeInTheDocument();
    expect(screen.getByText("pet", { selector: "strong" })).toBeInTheDocument();
    expect(screen.getByText("https://{env}.example.com/v2")).toBeInTheDocument();
    expect(screen.getByText("env = api (api, staging)")).toBeInTheDocument();
    // The TOC now lives in the version page's side panel, not the reader itself — assert the
    // dispatcher VersionPage renders it from instead of a `nav` element inside ContractReader.
    const toc = readerToc(MODEL_OPENAPI, t);
    const petsEntry = toc.find((e) => e.id === "tag-pets");
    expect(petsEntry?.label).toBe("pets");
    expect(petsEntry?.children).toContainEqual({ id: "op-0", label: "GET /pets/{id}" });
    expect(toc.map((e) => e.label)).toContain("Other operations");
    expect(toc.map((e) => e.id)).toContain("reader-webhooks");
    expect(screen.getByRole("heading", { level: 3, name: "pets" })).toBeInTheDocument();
    expect(screen.getByText("Everything about pets")).toBeInTheDocument();
    const get = screen.getByRole("article", { name: "/pets/{id}" });
    expect(within(get).getByText("GET")).toBeInTheDocument();
    expect(within(get).getByText("Deprecated")).toBeInTheDocument();
    expect(within(get).getByText("getPet")).toBeInTheDocument();
    expect(within(get).getByText("Read a pet")).toBeInTheDocument();
    expect(within(get).getByText("Security: as the document's")).toBeInTheDocument();
    expect(within(get).getByText("bearer")).toBeInTheDocument();
    const params = within(get).getByRole("table", { name: "Parameters" });
    expect(within(params).getAllByRole("row")).toHaveLength(3);
    expect(within(params).getByText("X-Tenant")).toBeInTheDocument();
    expect(within(params).getByText("header")).toBeInTheDocument();
    expect(within(params).getByText("More detail")).toBeInTheDocument();
    expect(within(get).getByText("200")).toBeInTheDocument();
    expect(within(get).getByText("The pet")).toBeInTheDocument();
    expect(within(get).getByRole("table", { name: "Headers of the 200 response" })).toHaveTextContent("X-Rate-Limit");
    expect(within(get).getByRole("link", { name: "Pet" })).toHaveAttribute("href", "#schema-0");
    const post = screen.getByRole("article", { name: "/pets" });
    expect(within(post).getByText("Request body *")).toBeInTheDocument();
    expect(within(post).getByText("Security: none")).toBeInTheDocument();
    expect(within(post).getByRole("tab", { name: "application/xml" })).toBeInTheDocument();
    await user.click(within(post).getByRole("tab", { name: "application/xml" }));
    expect(await within(post).findByText("No schema")).toBeInTheDocument();
    const del = screen.getByRole("article", { name: "/untagged" });
    expect(within(del).getByText("No responses declared")).toBeInTheDocument();
    expect(within(del).getByText("oauth [write]")).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "petChanged" })).toBeInTheDocument();
    const schemas = screen.getByRole("region", { name: "Schemas" });
    expect(within(schemas).getByRole("button", { name: "Pet" })).toBeInTheDocument();
    await user.click(within(schemas).getByRole("button", { name: "Pet" }));
    await user.click(await within(schemas).findByRole("button", { name: "Expand owner" }));
    expect(within(schemas).getByText("recursive → A pet")).toBeInTheDocument();
    const security = screen.getByRole("region", { name: "Security" });
    expect(within(security).getByText("http · bearer (JWT)")).toBeInTheDocument();
    expect(within(security).getByRole("table", { name: "Scopes of the authorizationCode flow" })).toHaveTextContent("Read things");
  });

  test("AsyncAPI: servers, channels, the 3.x verbs with the 2.x one dimmed, messages with headers, payload, examples", async () => {
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_ASYNCAPI } });
    render();
    expect(await screen.findByText("AsyncAPI 2.6.0")).toBeInTheDocument();
    expect(screen.getByText("Default content type: application/json")).toBeInTheDocument();
    expect(screen.getByText("kafka://broker.example.com:9092/prod")).toBeInTheDocument();
    const channel = screen.getByRole("article", { name: "lights/{id}/measured" });
    expect(within(channel).getByText("Measurements")).toBeInTheDocument();
    expect(within(channel).getByText("{id}")).toBeInTheDocument();
    expect(within(channel).getByRole("link", { name: "lightMeasured" })).toHaveAttribute("href", "#message-0");
    expect(within(channel).getByText("ghost")).toBeInTheDocument();
    expect(within(channel).getByText("Servers: prod")).toBeInTheDocument();
    const op = screen.getByRole("article", { name: "onMeasured" });
    expect(within(op).getByText("receive")).toBeInTheDocument();
    expect(within(op).getByText("(publish)")).toBeInTheDocument();
    expect(within(op).getByRole("link", { name: "lights/{id}/measured" })).toHaveAttribute("href", "#channel-0");
    const dim = screen.getByRole("article", { name: "dim" });
    expect(within(dim).getByText("send")).toBeInTheDocument();
    expect(within(dim).getByText("nowhere")).toBeInTheDocument();
    expect(within(dim).getByRole("link", { name: "lights/{id}/measured" })).toHaveAttribute("href", "#channel-0");
    const message = screen.getByRole("article", { name: "lightMeasured" });
    expect(within(message).getByText("Light measured")).toBeInTheDocument();
    expect(within(message).getByText("correlationId")).toBeInTheDocument();
    expect(within(message).getByText("lumens")).toBeInTheDocument();
    expect(within(message).getByText("minimum: 0")).toBeInTheDocument();
    expect(within(message).getByText('{"lumens":900}')).toBeInTheDocument();
    const raw = screen.getByRole("article", { name: "proto/raw" });
    expect(within(raw).getByText("inline")).toBeInTheDocument();
    expect(within(raw).getByText("not a schema")).toBeInTheDocument();
    expect(within(raw).getByText("message Raw {}")).toBeInTheDocument();
    expect(within(raw).getByText("Deprecated")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Security" })).toHaveTextContent("scramSha256");
  });

  test("ODCS: the header with a lifecycle pill, datasets as a nested table, quality, servers, team, SLA, support, price, truncation banner", async () => {
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_ODCS } });
    render();
    expect(await screen.findByRole("heading", { level: 3, name: "customers" })).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("ODCS v3.1.0")).toBeInTheDocument();
    expect(screen.getByText("Domain: sales")).toBeInTheDocument();
    expect(screen.getByText("This document is very large — the reader shows the first part of deep or wide schemas.")).toBeInTheDocument();
    expect(screen.getByText("Who buys.")).toBeInTheDocument();
    expect(screen.getByText("EU only.")).toBeInTheDocument();
    const table = screen.getByRole("table", { name: "Properties of customers" });
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows).toHaveLength(6);
    expect(rows[0]).toHaveAttribute("aria-level", "1");
    expect(within(rows[0]).getByText("id")).toBeInTheDocument();
    expect(within(rows[0]).getByText("PK 1")).toBeInTheDocument();
    expect(within(rows[0]).getByText("critical")).toBeInTheDocument();
    expect(within(rows[0]).getByText("format: uuid")).toBeInTheDocument();
    expect(within(rows[0]).getByText("Never null")).toBeInTheDocument();
    expect(rows[2]).toHaveAttribute("aria-level", "2");
    expect(within(rows[2]).getByText("P 1")).toBeInTheDocument();
    expect(within(rows[4]).getByText("lines[] items")).toBeInTheDocument();
    expect(within(rows[4]).getByText("cut")).toBeInTheDocument();
    expect(within(rows[5]).getByText("SUM(orders.amount)")).toBeInTheDocument();
    expect(screen.getByText("Enough rows")).toBeInTheDocument();
    expect(screen.getByText("mustBeGreaterThan 100")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Custom properties of customers" })).toHaveTextContent("crm-team");
    expect(screen.getByRole("table", { name: "Details of server prod" })).toHaveTextContent("db.example.com");
    expect(screen.getByText("Roles: reader (read)")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Team" })).toHaveTextContent("replaced by bob");
    expect(screen.getByRole("table", { name: "Roles" })).toHaveTextContent("Approvers: alice / carol");
    expect(screen.getByRole("table", { name: "Service levels" })).toHaveTextContent("4 h (8)");
    expect(screen.getByRole("table", { name: "Support" })).toHaveTextContent("slack");
    expect(screen.getByText("9.95 USD / megabyte")).toBeInTheDocument();
    expect(screen.getByText("refreshCadence")).toBeInTheDocument();
    const toc = readerToc(MODEL_ODCS, t);
    const datasetsEntry = toc.find((e) => e.id === "reader-datasets");
    expect(datasetsEntry?.children).toContainEqual({ id: "dataset-0", label: "customers" });
  });

  test("an unparseable stored document, an empty model, and a failed load each say so", async () => {
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_ERROR } });
    const first = render();
    expect(await screen.findByText("The stored document cannot be rendered")).toBeInTheDocument();
    expect(screen.getByText(/mapping values are not allowed here/)).toBeInTheDocument();
    first.unmount();
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_EMPTY } });
    const second = render();
    expect(await screen.findByText("No reader for this document type yet.")).toBeInTheDocument();
    second.unmount();
    serve(mockFetch, {});
    render();
    expect(await screen.findByRole("alert")).toHaveTextContent("Load failed (404)");
  });

  test("a finding's pointer highlights the nearest rendered element", async () => {
    serve(mockFetch, { [MODEL_URL]: { status: 200, body: MODEL_OPENAPI } });
    const { rerender } = render(null);
    await screen.findByRole("heading", { level: 3, name: "Pets" });
    rerender(<ContractReader contract={CONTRACT} version={VERSION} highlight={{ path: "/paths/~1pets~1{id}/get/responses/200/content/application~1json/schema/properties/id", nonce: 1 }} />);
    // The finding sits inside a response schema with no card of its own: its operation's card takes the ring.
    await waitFor(() => expect(screen.getByRole("article", { name: "/pets/{id}" })).toHaveAttribute("data-highlight", "true"));
    expect(screen.getByRole("article", { name: "/pets/{id}" })).toHaveAttribute("data-pointer", "/paths/~1pets~1{id}/get");
    rerender(<ContractReader contract={CONTRACT} version={VERSION} highlight={{ path: "/nowhere/at/all", nonce: 2 }} />);
    expect(screen.getByRole("article", { name: "/pets/{id}" })).toBeInTheDocument();
  });
});
