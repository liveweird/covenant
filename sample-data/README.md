# Sample-data examples

This folder contains raw, one-document-per-type examples for quick manual import through the
Covenant Import page. They are small format examples, not the curated reusable catalog landscape.
For the curated clean, warning-only, and intentionally invalid fixtures, use
[`samples/contracts/`](../samples/contracts/); its README documents the Python API loader and
`--check-only` validation. These examples are not seeded by a migration, so an environment still
starts with an empty catalog.

| File | Type | Standard |
| ---- | ---- | -------- |
| `petstore-openapi-3.1.yaml` | OPENAPI | OpenAPI 3.1.0 — one `GET /pets` operation, lint-clean under Spectral's `oas` ruleset |
| `streetlights-asyncapi-3.0.yaml` | ASYNCAPI | AsyncAPI 3.0.0 — one Kafka channel + a `receive` operation with a JSON Schema payload |
| `customer-view-odcs-3.1.yaml` | ODCS | Open Data Contract Standard v3.1.0 — one Postgres view with three columns, a server, a team and an SLA |

Import one of these files from the SPA's Import page, or use the curated loader when you need
repeatable sample records and expected findings. See `.claude/docs/contract-standards.md` for
what each format is and what Covenant reads out of it.
