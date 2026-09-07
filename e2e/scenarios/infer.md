# Contract inference

- **Spec**: [tests/infer.spec.ts](../tests/infer.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`)
- **Owns** (exclusive server-side state): its throwaway domains, systems and teams
  (`e2e-infer-http-*`, `e2e-infer-sql-*`), a PostgreSQL environment (`e2e-infer-env-*`) and the
  contracts each journey creates — all deleted by the end

## Scenario: user infers an OpenAPI draft from a pasted exchange and saves it as the contract's first version

1. The admin seeds a throwaway domain, system and team via the API, signs in, and opens
   `/contracts/infer` with OpenAPI kept as the type.
2. On the Paste tab they enter a GET exchange — `https://api.example.test/orders/42` answering
   `200` with `{"id":42,"total":19.5,"createdAt":"2026-09-07T10:00:00Z"}` — and add it.
   - *Expected*: the sample list reads "1 sample".
3. They click Generate draft.
   - *Expected*: the preview document templates the numeric segment as `/orders/{orderId}`, and
     the findings panel carries an `INFER_PATH_TEMPLATED` note.
4. They click Open in editor.
   - *Expected*: `/contracts/import` opens with the generated text in the editor, OpenAPI as the
     type, and the Name/Version fields prefilled from the document's own title and version.
5. They pick the seeded system and team as owner and click Import.
   - *Expected*: the import succeeds; opening the version shows it as a Draft.
6. Teardown: the contract, system, domain and team are deleted via the API.

## Scenario: admin describes the users table through an environment and imports the inferred ODCS draft

1. The admin seeds a throwaway domain, system and team via the API, signs in, and registers a
   PostgreSQL environment on that system pointing at the stack's own database
   (`jdbc:postgresql://postgres:5432/covenant`, `covenant`/`covenant`).
2. They open `/contracts/infer`, switch the type to ODCS, and open the Observe tab.
3. They pick the environment and, from the relations picker, `public.users (table)`, then click
   Describe.
   - *Expected*: a result line reports the number of columns described.
4. They click Add to samples.
   - *Expected*: the sample list reads "1 sample".
5. They click Generate draft.
   - *Expected*: the preview document declares the dataset `physicalType: table`, a property
     `name: email`, and the primary key column carries `primaryKey: true`.
6. They click Open in editor.
   - *Expected*: `/contracts/import` opens with the generated text and ODCS as the type.
7. They pick the seeded system and team as owner and click Import.
   - *Expected*: the import succeeds; opening the version shows it as a Draft.
8. Teardown: the environment, the contract, the system, the domain and the team are deleted via
   the API.

## Not covered here (and why)

- **The HAR upload tab and the Kafka observe leg** — no HAR fixture and no broker in the compose
  stack; `har.test.ts` and `InferObservePanel.test.tsx` (SPA) plus `ObserveKafkaTest` (server) pin
  them.
- **Every inference heuristic** (mixed types, CloudEvents, security-scheme detection, the budget
  truncation, the view-nullability and schema-qualified notes) — pinned by `SchemaInferenceTest`,
  `InferenceDocumentsTest` and the per-builder server tests; this journey only proves the samples
  reach a draft the ordinary editor can save.
