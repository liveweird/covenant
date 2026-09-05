import { readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";
import { check } from "../src/check.ts";

const fixture = (name: string) => readFileSync(new URL(`./fixtures/${name}`, import.meta.url), "utf8");

describe("check — OPENAPI", () => {
  test("a lint-clean 3.1 document yields no ERRORs and reports the engines", async () => {
    const { findings, engine } = await check({ type: "OPENAPI", content: fixture("openapi/petstore-3.1.yaml") });
    expect(findings.filter((f) => f.severity === "ERROR")).toEqual([]);
    expect(engine.map((e) => e.name)).toContain("@stoplight/spectral-core");
  });

  test("a structurally broken 3.0 document fails oas3-schema as SCHEMA with a position", async () => {
    const { findings } = await check({ type: "OPENAPI", content: fixture("openapi/missing-info-3.0.yaml") });
    const schema = findings.find((f) => f.code === "oas3-schema");
    expect(schema).toMatchObject({ severity: "ERROR", source: "SCHEMA" });
    expect(schema?.line).toBeGreaterThan(0);
    // The lint pass still runs beside the structural failure.
    expect(findings.some((f) => f.code === "operation-operationId")).toBe(true);
  });

  test("Swagger 2.0 is refused, not linted", async () => {
    const { findings } = await check({ type: "OPENAPI", content: fixture("openapi/swagger-2.0.yaml") });
    expect(findings).toEqual([
      expect.objectContaining({ severity: "ERROR", source: "SCHEMA", code: "unsupported-version", path: "/swagger" }),
    ]);
  });

  test("an external $ref is refused before any engine resolves it", async () => {
    const { findings } = await check({ type: "OPENAPI", content: fixture("openapi/external-ref-3.0.yaml") });
    expect(findings).toEqual([
      expect.objectContaining({
        code: "external-ref-not-allowed",
        path: "/paths/~1things/get/responses/200/content/application~1json/schema/$ref",
      }),
    ]);
  });

  test("unparseable YAML answers a SYNTAX finding with its position", async () => {
    const { findings } = await check({ type: "OPENAPI", content: "openapi: 3.1.0\ninfo: [unclosed\n" });
    expect(findings[0]).toMatchObject({ severity: "ERROR", source: "SYNTAX", code: "parser" });
    expect(findings[0]?.line).toBeGreaterThan(0);
  });
});

describe("check — ASYNCAPI", () => {
  test("a valid 3.0 document has no ERRORs (semantic + lint passes both ran)", async () => {
    const { findings } = await check({ type: "ASYNCAPI", content: fixture("asyncapi/streetlights-3.0.yaml") });
    expect(findings.filter((f) => f.severity === "ERROR")).toEqual([]);
  });

  test("a valid 2.6 document has no ERRORs", async () => {
    const { findings } = await check({ type: "ASYNCAPI", content: fixture("asyncapi/streetlights-2.6.yaml") });
    expect(findings.filter((f) => f.severity === "ERROR")).toEqual([]);
  });

  test("a document whose channels are not a map fails the AsyncAPI schema as SCHEMA", async () => {
    const { findings } = await check({ type: "ASYNCAPI", content: fixture("asyncapi/missing-channels-3.0.yaml") });
    const errors = findings.filter((f) => f.severity === "ERROR");
    expect(errors.length).toBeGreaterThan(0);
    expect(errors.every((f) => f.source === "SCHEMA")).toBe(true);
  });

  test("an Avro payload is left to the JVM — no 'no schema parser registered' noise", async () => {
    const { findings } = await check({ type: "ASYNCAPI", content: fixture("asyncapi/avro-payload-3.0.yaml") });
    expect(findings.filter((f) => f.severity === "ERROR")).toEqual([]);
    expect(findings.some((f) => /schema parser/i.test(f.message))).toBe(false);
  });
});

describe("check — ODCS", () => {
  test("is accepted and answers no findings (the JVM owns ODCS in milestone 1)", async () => {
    const { findings } = await check({ type: "ODCS", content: fixture("odcs/minimal-3.1.yaml") });
    expect(findings).toEqual([]);
  });
});
