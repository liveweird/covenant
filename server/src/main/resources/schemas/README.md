# Vendored JSON Schemas

The official JSON Schemas the JVM validators (`contracts/checks/`) check contract documents
against, copied verbatim from upstream so validation is **offline and deterministic** — a user
document can never make the server fetch a schema. Never hand-edit a file here: re-vendor it
(the same `curl` below) and bump this table in the same commit; the validator picks the file by
the document's own version field (`asyncapi:` / `apiVersion:`), so adding a version = one file +
one table row + one `SchemaRegistry` entry.

| File | Upstream | Version | Fetched | Draft |
| ---- | -------- | ------- | ------- | ----- |
| `asyncapi/2.6.0.json` | `https://raw.githubusercontent.com/asyncapi/spec-json-schemas/master/schemas/2.6.0-without-$id.json` | 2.6.0 | 2026-09-05 | draft-07 |
| `asyncapi/3.0.0.json` | `…/schemas/3.0.0-without-$id.json` | 3.0.0 | 2026-09-05 | draft-07 |
| `asyncapi/3.1.0.json` | `…/schemas/3.1.0-without-$id.json` | 3.1.0 | 2026-09-05 | draft-07 |
| `odcs/odcs-json-schema-v3.0.0.json` | `https://raw.githubusercontent.com/bitol-io/open-data-contract-standard/main/schema/odcs-json-schema-v3.0.0.json` | v3.0.0 | 2026-09-05 | 2019-09 |
| `odcs/odcs-json-schema-v3.0.1.json` | `…/schema/odcs-json-schema-v3.0.1.json` | v3.0.1 | 2026-09-05 | 2019-09 |
| `odcs/odcs-json-schema-v3.0.2.json` | `…/schema/odcs-json-schema-v3.0.2.json` | v3.0.2 | 2026-09-05 | 2019-09 |
| `odcs/odcs-json-schema-v3.1.0.json` | `…/schema/odcs-json-schema-v3.1.0.json` | v3.1.0 | 2026-09-05 | 2019-09 |

Notes:

- The AsyncAPI files are the `-without-$id` variants: the plain ones carry `$id`-based internal
  references (`http://asyncapi.com/definitions/…`) that a strictly offline validator would try to
  resolve remotely. The npm package `@asyncapi/specs` ships the same files.
- OpenAPI needs no schema file — `swagger-parser-v3` validates OpenAPI 3.0/3.1 structurally.
- JSON Schema 2020-12 (AsyncAPI payload meta-validation) is bundled inside networknt.
- Licences: AsyncAPI schemas Apache-2.0 (asyncapi/spec-json-schemas), ODCS Apache-2.0 (bitol-io).
