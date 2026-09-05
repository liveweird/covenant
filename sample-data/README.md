# Sample contracts

One well-formed document per contract type Covenant supports, for exercising the app by hand
(paste or pick them on the Import page once the contracts feature lands) and for the checker's
fixtures (`checker/test/fixtures/` holds the same OpenAPI/AsyncAPI documents plus deliberately
broken siblings). Deliberately NOT seeded: no migration inserts contracts, so every environment
still comes up with an empty catalog.

| File | Type | Standard |
| ---- | ---- | -------- |
| `petstore-openapi-3.1.yaml` | OPENAPI | OpenAPI 3.1.0 — one `GET /pets` operation, lint-clean under Spectral's `oas` ruleset |
| `streetlights-asyncapi-3.0.yaml` | ASYNCAPI | AsyncAPI 3.0.0 — one Kafka channel + a `receive` operation with a JSON Schema payload |
| `customer-view-odcs-3.1.yaml` | ODCS | Open Data Contract Standard v3.1.0 — one Postgres view with three columns, a server, a team and an SLA |

See `.claude/docs/contract-standards.md` for what each format is and what Covenant reads out of it.
