import { describe, expect, test } from "vitest";
import { readHar } from "./har";

const SECRET_TOKEN = "super-secret-value";
const SECRET_COOKIE = "session=cookieSecretValue123";

function entry(request: Record<string, unknown>, response: Record<string, unknown>) {
  return {
    request: { httpVersion: "HTTP/1.1", cookies: [], headersSize: -1, bodySize: -1, ...request },
    response: { httpVersion: "HTTP/1.1", cookies: [], headersSize: -1, bodySize: -1, redirectURL: "", ...response },
  };
}

const FIXTURE = {
  log: {
    version: "1.2",
    creator: { name: "test", version: "1.0" },
    entries: [
      // 1: authenticated GET with a secret-shaped query param and a cookie — never surfaced
      entry(
        {
          method: "GET",
          url: "https://api.example.test/orders/42?token=abc&page=2",
          headers: [
            { name: "Authorization", value: `Bearer ${SECRET_TOKEN}` },
            { name: "Cookie", value: SECRET_COOKIE },
            { name: "Accept", value: "application/json" },
          ],
          queryString: [
            { name: "token", value: "abc" },
            { name: "page", value: "2" },
          ],
        },
        { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: '{"id":42}' } },
      ),
      // 2: POST with a JSON request body and a 201 JSON response
      entry(
        {
          method: "POST",
          url: "https://api.example.test/orders",
          headers: [{ name: "Content-Type", value: "application/json" }],
          queryString: [],
          postData: { mimeType: "application/json", text: '{"name":"widget"}' },
        },
        { status: 201, headers: [], content: { mimeType: "application/json", text: '{"id":43}' } },
      ),
      // 3: an OPTIONS preflight — dropped regardless of content type
      entry(
        { method: "OPTIONS", url: "https://api.example.test/orders/42", headers: [], queryString: [] },
        { status: 204, headers: [], content: { mimeType: "text/plain", text: "" } },
      ),
      // 4: an image response — dropped, not JSON-like on either side
      entry(
        { method: "GET", url: "https://api.example.test/logo.png", headers: [], queryString: [] },
        { status: 200, headers: [{ name: "Content-Type", value: "image/png" }], content: { mimeType: "image/png" } },
      ),
      // 5: a body-less 204 answering a JSON-accepting DELETE — kept
      entry(
        { method: "DELETE", url: "https://api.example.test/orders/42", headers: [{ name: "Accept", value: "application/json" }], queryString: [] },
        { status: 204, headers: [], content: { mimeType: "", text: "" } },
      ),
      // 6: a base64-encoded JSON response — decoded
      entry(
        { method: "GET", url: "https://api.example.test/accounts/1", headers: [{ name: "Accept", value: "application/json" }], queryString: [] },
        {
          status: 200,
          headers: [{ name: "Content-Type", value: "application/json" }],
          content: { mimeType: "application/json", text: btoa('{"decoded":true}'), encoding: "base64" },
        },
      ),
      // 7: a second origin
      entry(
        { method: "GET", url: "https://reports.example.test/status", headers: [], queryString: [] },
        { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: '{"ok":true}' } },
      ),
    ],
  },
};

describe("readHar", () => {
  test("keeps JSON-relevant entries, drops OPTIONS preflights and non-JSON responses, and reports total vs kept", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    expect(result.total).toBe(7);
    expect(result.exchanges).toHaveLength(5);
    expect(result.exchanges.some((e) => e.url.endsWith("/logo.png"))).toBe(false);
    expect(result.exchanges.some((e) => e.method === "OPTIONS")).toBe(false);
  });

  test("sorts origins by descending kept count", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    expect(result.origins).toEqual([
      { origin: "https://api.example.test", count: 4 },
      { origin: "https://reports.example.test", count: 1 },
    ]);
  });

  test("blanks a secret-shaped query value but keeps an ordinary one, and drops the query string from the url", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const exchange = result.exchanges.find((e) => e.url === "https://api.example.test/orders/42" && e.method === "GET");
    expect(exchange).toBeDefined();
    expect(exchange?.query).toEqual({ token: "", page: "2" });
  });

  test("reads the Authorization scheme word and never keeps the header value or the cookie anywhere in the result", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const exchange = result.exchanges.find((e) => e.url === "https://api.example.test/orders/42" && e.method === "GET");
    expect(exchange?.authorizationScheme).toBe("bearer");
    const serialized = JSON.stringify(result);
    expect(serialized).not.toContain(SECRET_TOKEN);
    expect(serialized).not.toContain(SECRET_COOKIE);
    expect(serialized).not.toContain("cookieSecretValue123");
  });

  test("keeps header NAMES only, lower-cased", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const exchange = result.exchanges.find((e) => e.url === "https://api.example.test/orders/42" && e.method === "GET");
    expect(exchange?.requestHeaders).toEqual(expect.arrayContaining(["authorization", "cookie", "accept"]));
    expect(exchange?.requestHeaders?.every((h) => h === h.toLowerCase())).toBe(true);
    expect(exchange?.responseHeaders).toEqual(["content-type"]);
  });

  test("keeps a body-less 204 answering a JSON-accepting request", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const deleteExchange = result.exchanges.find((e) => e.method === "DELETE");
    expect(deleteExchange).toBeDefined();
    expect(deleteExchange?.status).toBe(204);
  });

  test("base64-decodes a response body", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const account = result.exchanges.find((e) => e.url.endsWith("/accounts/1"));
    expect(account?.responseBody).toBe('{"decoded":true}');
  });

  test("keeps a JSON request body", () => {
    const result = readHar(JSON.stringify(FIXTURE));
    const post = result.exchanges.find((e) => e.method === "POST");
    expect(post?.requestBody).toBe('{"name":"widget"}');
    expect(post?.status).toBe(201);
  });

  test("throws a plain error for a non-HAR JSON document", () => {
    expect(() => readHar(JSON.stringify({ not: "a har file" }))).toThrow("not a HAR file");
  });

  test("throws before parsing when the text exceeds the 30 MiB ceiling", () => {
    const oversized = "a".repeat(30 * 1024 * 1024 + 1);
    expect(() => readHar(oversized)).toThrow("too large");
  });

  test("drops an entry whose url does not parse, and one whose declared base64 body is not decodable", () => {
    const fixture = {
      log: {
        version: "1.2",
        creator: { name: "test", version: "1.0" },
        entries: [
          entry({ method: "GET", url: "not a url", headers: [], queryString: [] }, { status: 200, headers: [], content: {} }),
          entry(
            { method: "GET", url: "https://api.example.test/broken", headers: [{ name: "Accept", value: "application/json" }], queryString: [] },
            { status: 200, headers: [{ name: "Content-Type", value: "application/json" }], content: { mimeType: "application/json", text: "not-valid-base64!!", encoding: "base64" } },
          ),
        ],
      },
    };
    const result = readHar(JSON.stringify(fixture));
    expect(result.exchanges).toHaveLength(1);
    expect(result.exchanges[0].responseBody).toBeNull();
  });
});
