// -- test scaffolding: typed render models for the reader tests. Typed as RenderModel so a spec
// change that renames a field fails `tsc` here before any test runs.
import type { AsyncApiModel, OdcsModel, OpenApiModel, RenderModel, SchemaNode } from "../api/versions";

export function node(partial: Partial<SchemaNode> & { pointer: string }): SchemaNode {
  return {
    types: [],
    nullable: false,
    deprecated: false,
    readOnly: false,
    writeOnly: false,
    properties: [],
    tupleItems: [],
    allOf: [],
    anyOf: [],
    oneOf: [],
    enumValues: [],
    examples: [],
    constraints: [],
    ...partial,
  };
}

const PET = node({
  pointer: "/components/schemas/Pet",
  types: ["object"],
  title: "A pet",
  properties: [
    { name: "id", required: true, schema: node({ pointer: "/components/schemas/Pet/properties/id", types: ["integer"], format: "int64", readOnly: true, examples: ["7"] }) },
    { name: "name", required: true, schema: node({ pointer: "/components/schemas/Pet/properties/name", types: ["string"], nullable: true, constraints: [{ keyword: "minLength", value: "1" }] }) },
    { name: "kind", required: false, schema: node({ pointer: "/components/schemas/Pet/properties/kind", types: ["string"], enumValues: ["dog", "cat"], defaultValue: "dog", deprecated: true }) },
    { name: "owner", required: false, schema: node({ pointer: "/components/schemas/Owner", ref: "#/components/schemas/Owner", types: ["object"], properties: [{ name: "pet", required: false, schema: node({ pointer: "/components/schemas/Owner/properties/pet", ref: "#/components/schemas/Pet", marker: "CIRCULAR", title: "A pet" }) }] }) },
    { name: "external", required: false, schema: node({ pointer: "/components/schemas/Pet/properties/external", ref: "https://example.com/x.yaml#/Thing", marker: "UNRESOLVED" }) },
    { name: "tags", required: false, schema: node({ pointer: "/components/schemas/Pet/properties/tags", types: ["object"], additionalProperties: node({ pointer: "/components/schemas/Pet/properties/tags/additionalProperties", types: ["string"] }), additionalPropertiesAllowed: true }) },
  ],
  discriminator: { propertyName: "kind", mapping: [] },
});

export const OPENAPI: OpenApiModel = {
  info: { title: "Pets", version: "2.1.0", description: "The **pet** store.", contact: { name: "API team", email: "api@example.com" }, license: { name: "MIT", url: "https://opensource.org/licenses/MIT" }, termsOfService: "https://example.com/tos" },
  servers: [{ url: "https://{env}.example.com/v2", description: "Per environment", variables: [{ name: "env", default: "api", enumValues: ["api", "staging"] }] }],
  tags: [{ name: "pets", description: "Everything about pets" }, { name: "admin" }],
  operations: [
    {
      pointer: "/paths/~1pets~1{id}/get",
      method: "GET",
      path: "/pets/{id}",
      operationId: "getPet",
      summary: "Read a pet",
      description: "Reads one pet.",
      tags: ["pets"],
      deprecated: true,
      parameters: [
        { pointer: "/paths/~1pets~1{id}/get/parameters/0", name: "id", location: "path", required: true, deprecated: false, schema: node({ pointer: "/p/id", types: ["integer"] }), examples: [], content: [] },
        { pointer: "/paths/~1pets~1{id}/get/parameters/1", name: "verbose", location: "query", required: false, deprecated: false, description: "More detail", schema: node({ pointer: "/p/verbose", types: ["boolean"] }), examples: [{ name: "example", value: "true" }], content: [] },
        { pointer: "/components/parameters/Tenant", name: "X-Tenant", location: "header", required: true, deprecated: false, schema: node({ pointer: "/p/tenant", types: ["string"] }), examples: [], content: [] },
      ],
      responses: [
        {
          pointer: "/components/responses/PetFound",
          status: "200",
          description: "The pet",
          headers: [{ name: "X-Rate-Limit", required: false, deprecated: false, description: "Remaining", schema: node({ pointer: "/h", types: ["integer"] }) }],
          content: [{ mediaType: "application/json", schema: node({ pointer: "/r/schema", ref: "#/components/schemas/Pet", types: ["object"], title: "A pet" }), examples: [] }],
        },
        { pointer: "/paths/~1pets~1{id}/get/responses/404", status: "404", description: "Not here", headers: [], content: [] },
      ],
      servers: [],
    },
    {
      pointer: "/paths/~1pets/post",
      method: "POST",
      path: "/pets",
      tags: ["pets"],
      deprecated: false,
      parameters: [],
      requestBody: { pointer: "/components/requestBodies/NewPet", description: "The pet to store", required: true, content: [{ mediaType: "application/json", schema: PET, examples: [{ name: "rex", summary: "A dog", value: '{"id":1,"name":"Rex"}' }] }, { mediaType: "application/xml", examples: [] }] },
      responses: [{ pointer: "/paths/~1pets/post/responses/201", status: "201", description: "Created", headers: [], content: [] }],
      security: [],
      servers: [],
    },
    { pointer: "/paths/~1untagged/delete", method: "DELETE", path: "/untagged", tags: [], deprecated: false, parameters: [], responses: [], security: [{ schemes: [{ name: "oauth", scopes: ["write"] }] }], servers: [] },
  ],
  webhooks: [{ pointer: "/webhooks/petChanged/post", method: "POST", path: "petChanged", tags: [], deprecated: false, parameters: [], responses: [{ pointer: "/webhooks/petChanged/post/responses/200", status: "200", description: "ack", headers: [], content: [] }], servers: [] }],
  security: [{ schemes: [{ name: "bearer", scopes: [] }] }],
  securitySchemes: [
    { name: "bearer", pointer: "/components/securitySchemes/bearer", type: "http", scheme: "bearer", bearerFormat: "JWT", description: "A JWT", flows: [] },
    { name: "oauth", pointer: "/components/securitySchemes/oauth", type: "oauth2", flows: [{ type: "authorizationCode", authorizationUrl: "https://auth.example.com/authorize", tokenUrl: "https://auth.example.com/token", scopes: [{ key: "read", value: "Read things" }, { key: "write", value: "Write things" }] }] },
  ],
  schemas: [{ name: "Pet", pointer: "/components/schemas/Pet", schema: PET }, { name: "Owner", pointer: "/components/schemas/Owner", schema: node({ pointer: "/components/schemas/Owner", types: ["object"] }) }],
  externalDocs: { url: "https://docs.example.com", description: "The handbook" },
};

const ASYNCAPI: AsyncApiModel = {
  info: { title: "Lights", version: "1.0.0" },
  tags: [],
  defaultContentType: "application/json",
  servers: [{ name: "prod", host: "broker.example.com:9092", pathname: "/prod", protocol: "kafka", protocolVersion: "3.6", security: ["sasl"], tags: [], bindings: ["kafka"] }],
  channels: [
    {
      pointer: "/channels/lights~1{id}~1measured",
      name: "lights/{id}/measured",
      address: "lights/{id}/measured",
      description: "Measurements",
      servers: ["prod"],
      parameters: [{ name: "id", description: "The light", enumValues: ["a", "b"], schema: node({ pointer: "/c/p/id", types: ["string"] }) }],
      messages: [{ name: "lightMeasured", target: "lightMeasured" }, { name: "ghost" }],
      bindings: ["kafka"],
    },
  ],
  operations: [
    { pointer: "/channels/lights~1{id}~1measured/publish", name: "onMeasured", action: "receive", legacyAction: "publish", channel: "lights/{id}/measured", messages: [{ name: "lightMeasured", target: "lightMeasured" }], summary: "A light measured", security: ["sasl"], tags: ["lights"], bindings: [] },
    { pointer: "/operations/dim", name: "dim", action: "send", channel: "nowhere", messages: [], reply: "lights/{id}/measured", security: [], tags: [], bindings: [] },
  ],
  messages: [
    {
      key: "lightMeasured",
      pointer: "/components/messages/lightMeasured",
      name: "lightMeasured",
      title: "Light measured",
      contentType: "application/json",
      headers: node({ pointer: "/components/messages/lightMeasured/headers", types: ["object"], properties: [{ name: "correlationId", required: false, schema: node({ pointer: "/h/c", types: ["string"] }) }] }),
      payload: node({ pointer: "/components/messages/lightMeasured/payload", types: ["object"], properties: [{ name: "lumens", required: true, schema: node({ pointer: "/p/l", types: ["integer"], constraints: [{ keyword: "minimum", value: "0" }] }) }] }),
      correlationId: "$message.header#/correlationId",
      examples: [{ name: "bright", value: '{"lumens":900}' }],
      tags: [],
      bindings: [],
      deprecated: false,
      inline: false,
    },
    { key: "proto/raw", pointer: "/channels/proto/messages/raw", schemaFormat: "application/vnd.google.protobuf;version=3", payload: node({ pointer: "/channels/proto/messages/raw/payload/schema", raw: "message Raw {}", format: "application/vnd.google.protobuf;version=3" }), examples: [], tags: [], bindings: [], deprecated: true, inline: true },
  ],
  schemas: [],
  securitySchemes: [{ name: "sasl", pointer: "/components/securitySchemes/sasl", type: "scramSha256", flows: [] }],
};

const ODCS: OdcsModel = {
  id: "7b1b6f2e",
  name: "customers",
  version: "1.2.0",
  status: "active",
  domain: "sales",
  dataProduct: "crm",
  tenant: "acme",
  tags: ["pii"],
  contractCreatedTs: "2026-01-01T00:00:00Z",
  description: { purpose: "Who buys.", limitations: "EU only.", usage: "Analytics.", authoritativeDefinitions: [{ type: "businessDefinition", url: "https://wiki.example.com/customers" }], customProperties: [{ key: "steward", value: "alice" }] },
  datasets: [
    {
      pointer: "/schema/0",
      name: "customers",
      physicalName: "crm.customers",
      physicalType: "table",
      businessName: "Customers",
      description: "One row per customer.",
      dataGranularityDescription: "Per customer",
      tags: ["core"],
      properties: [
        { pointer: "/schema/0/properties/0", name: "id", logicalType: "string", physicalType: "uuid", required: true, unique: true, primaryKey: true, primaryKeyPosition: 1, partitioned: false, classification: "internal", criticalDataElement: true, transformSourceObjects: [], examples: ["8d1b..."], tags: [], options: [{ key: "format", value: "uuid" }], quality: [{ pointer: "/schema/0/properties/0/quality/0", type: "text", description: "Never null", thresholds: [] }], properties: [], customProperties: [] },
        {
          pointer: "/schema/0/properties/1",
          name: "address",
          logicalType: "object",
          required: false,
          unique: false,
          primaryKey: false,
          partitioned: false,
          criticalDataElement: false,
          transformSourceObjects: [],
          examples: [],
          tags: [],
          options: [],
          quality: [],
          properties: [
            { pointer: "/schema/0/properties/1/properties/0", name: "city", logicalType: "string", required: false, unique: false, primaryKey: false, partitioned: true, partitionKeyPosition: 1, criticalDataElement: false, transformSourceObjects: [], examples: [], tags: [], options: [], quality: [], properties: [], customProperties: [] },
            { pointer: "/schema/0/properties/1/properties/1", name: "lines", logicalType: "array", required: false, unique: false, primaryKey: false, partitioned: false, criticalDataElement: false, transformSourceObjects: [], examples: [], tags: [], options: [], quality: [], items: { pointer: "/schema/0/properties/1/properties/1/items", name: "", logicalType: "string", physicalName: "lines_enc", required: false, unique: false, primaryKey: false, partitioned: false, criticalDataElement: false, transformSourceObjects: [], examples: [], tags: [], options: [], quality: [], properties: [], customProperties: [], marker: "TRUNCATED" }, properties: [], customProperties: [] },
          ],
          customProperties: [],
        },
        { pointer: "/schema/0/properties/2", name: "total", logicalType: "number", required: false, unique: false, primaryKey: false, partitioned: false, criticalDataElement: false, transformSourceObjects: ["orders"], transformLogic: "SUM(orders.amount)", examples: [], tags: [], options: [], quality: [], properties: [], customProperties: [{ key: "unit", value: "EUR" }] },
      ],
      quality: [{ pointer: "/schema/0/quality/0", type: "library", rule: "rowCount", name: "Enough rows", dimension: "completeness", severity: "error", thresholds: [{ key: "mustBeGreaterThan", value: "100" }], description: "At least a hundred" }],
      authoritativeDefinitions: [],
      customProperties: [{ key: "owner", value: "crm-team" }],
    },
  ],
  servers: [{ pointer: "/servers/0", server: "prod", type: "postgres", environment: "production", description: "The warehouse", details: [{ key: "host", value: "db.example.com" }, { key: "port", value: "5432" }], roles: [{ role: "reader", access: "read", customProperties: [] }] }],
  team: [{ username: "alice", role: "owner", dateIn: "2025-01-01", dateOut: "2026-01-01", replacedByUsername: "bob" }],
  roles: [{ role: "analyst", access: "read", firstLevelApprovers: "alice", secondLevelApprovers: "carol", customProperties: [] }],
  slaDefaultElement: "updated_at",
  slaProperties: [{ property: "latency", value: "4", unit: "h", valueExt: "8", element: "updated_at", driver: "operational" }],
  support: [{ channel: "#crm", tool: "slack", url: "https://slack.example.com", scope: "interactive" }],
  price: { priceAmount: "9.95", priceCurrency: "USD", priceUnit: "megabyte" },
  authoritativeDefinitions: [{ type: "privacy-statement", url: "https://example.com/privacy" }],
  customProperties: [{ key: "refreshCadence", value: "daily" }],
};

export const MODEL_OPENAPI: RenderModel = { type: "OPENAPI", specVersion: "3.0.3", truncated: false, openApi: OPENAPI };
export const MODEL_ASYNCAPI: RenderModel = { type: "ASYNCAPI", specVersion: "2.6.0", truncated: false, asyncApi: ASYNCAPI };
export const MODEL_ODCS: RenderModel = { type: "ODCS", specVersion: "v3.1.0", truncated: true, odcs: ODCS };
export const MODEL_ERROR: RenderModel = { type: "OPENAPI", truncated: false, error: { severity: "ERROR", source: "SYNTAX", code: "YAML_PARSE", message: "mapping values are not allowed here" } };
export const MODEL_EMPTY: RenderModel = { type: "OPENAPI", truncated: false };
