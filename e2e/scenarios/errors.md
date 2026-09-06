# Errors report

- **Spec**: [tests/errors.spec.ts](../tests/errors.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`)
- **Owns** (exclusive server-side state): its throwaway domain, system, team and contract
  (`e2e-errors-*` names), API-seeded and deleted by the end of the file

The journey is API-seeded rather than driven through the import/editor UI (that is
`contracts.spec.ts`'s job) — this spec is about the report page reading the stored findings, not
about producing them.

## Scenario: a waived soft error lands on the Errors report, the severity chips narrow it, and the version link opens it

1. Through the admin's API session, a domain/system/team-owned OpenAPI contract is created with a
   clean `1.0.0` first version, then a `1.1.0` version with a broken internal `$ref` is stored via
   `POST …/versions?allowInvalid=true` — the same waiver the Save-anyway dialog drives.
   - *Expected*: the second version stores (`201`) despite the broken reference.
2. The admin signs in and opens `/errors`.
   - *Expected*: the "Errors" heading renders.
3. They open the filter panel and search for the contract's name.
   - *Expected*: a link "Open version 1.1.0 of `<contract>`" appears, and its row shows an
     `OAS_PARSE` finding badge.
4. They switch off the "Error" chip in the "Severity" group.
   - *Expected*: the `OAS_PARSE` badge disappears from the row (asserted on the badge itself, not
     on an empty table — a lint `WARN` may still keep the row or a sibling row visible).
5. They switch the "Error" chip back on.
   - *Expected*: the badge and the version link return.
6. They follow the version link.
   - *Expected*: the URL lands on `/contracts/{id}/versions/{versionId}` for the broken version.
7. The admin's API session deletes the contract, the system, the domain and the team.

## Not covered here (and why)

- **The server-side filter/facet matrix (severity, source, lifecycle, agreement with `facets`)** —
  pinned by `ContractErrorsTest`/`ErrorFacetsFoldTest` (server) and `Errors.test.tsx` (SPA).
- **The Save-anyway UI journey that produces a soft-error version** — covered by
  `contracts.spec.ts`; this spec seeds the same shape through the API to keep its focus on the
  report page.
