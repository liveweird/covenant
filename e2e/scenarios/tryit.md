# Try it (live conformance through an environment)

- **Spec**: [tests/tryit.spec.ts](../tests/tryit.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`)
- **Owns** (exclusive server-side state): its throwaway domain (`e2e-dom-t*`), system (`e2e-sys-t*`),
  team (`e2e-team-t*`), environment (`e2e-env-t*`) and contract (`e2e-accounts*`) — all deleted by the end

## Scenario: admin reads the users table through an environment and the sample is measured against the ODCS contract

1. The admin creates a throwaway domain, system and team, then an environment on that system whose
   only target is the stack's own PostgreSQL (`jdbc:postgresql://postgres:5432/covenant`,
   `covenant`/`covenant`).
2. They pick the ODCS type on the import page and paste a document declaring the dataset `users` (a table) with the columns `id`
   (integer, required), `email` (string, required), `name` (string) and `bogus` (string), owned by
   the team, and open its version.
3. They click Try it.
   - *Expected*: the drawer opens titled "Try <contract> 1.0.0"; the environment and the dataset are
     preselected — the only PostgreSQL environment of the system, the only table-like dataset.
4. They click Run.
   - *Expected*: the statement `SELECT * FROM "users" LIMIT 50` is shown; the sample table lists the
     seed administrator's email under an `email` column carrying its database type; the findings
     show `COLUMN_MISSING` (the declared `bogus` column the table lacks) and `COLUMN_EXTRA`
     (the table's undeclared columns).
5. They close the drawer and delete the contract, the environment, the system, the domain and the team.
   - *Expected*: every delete lands.

## Not covered here (and why)

- **The HTTP leg** (redirects, header rules, body caps, the response measured against the schema)
  and **the Kafka legs** (publish for writers only, the tail read) — no target of the right shape in
  the compose stack; `TryHttpTest` (a loopback fixture), `TryKafkaTest` (Testcontainers Kafka) and
  `TryItDrawer.test.tsx` (the three panels over stubbed fetches) pin them.
- **The read-only guarantees, the identifier grammar, the classified 502s, the rate limit** —
  `TrySqlTest`, `TryHttpTest` and `security.md`.
