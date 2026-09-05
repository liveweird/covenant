# Environments (the try-it targets registry)

- **Spec**: [tests/environments.spec.ts](../tests/environments.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`); one throwaway reader
- **Owns** (exclusive server-side state): its throwaway domain (`e2e-dom-e*`), system (`e2e-sys-e*`),
  environment (`e2e-env-*`) and user — all deleted by the end

## Scenario: admin creates an environment with HTTP and PostgreSQL targets, edits it keeping the stored password, and deletes it

1. The admin creates a throwaway domain and system through the registry pages.
2. On the Environments page they click New environment, pick the system, name it, enter the
   HTTP base URL `http://checker:9090`, enable the PostgreSQL target with the stack's own
   database (`jdbc:postgresql://postgres:5432/covenant`, `covenant`/`covenant`) and click Create.
   - *Expected*: the row appears with the HTTP and PostgreSQL badges.
3. They open Edit from the row's operations.
   - *Expected*: the password field is blank and says "Leave blank to keep the stored password".
4. They change the description and Save.
   - *Expected*: the row shows the new description and STILL shows the PostgreSQL badge — the
     stored password was kept.
5. They delete the environment from its row, then the system and the domain.
   - *Expected*: the row disappears; each registry delete lands.

## Scenario: a regular user reads the environments list without controls

1. The admin creates a throwaway user; the user signs in and opens Environments.
   - *Expected*: the page renders without New environment or any row operations.
2. The admin signs back in and deletes the user.

## Not covered here (and why)

- **The shape rules, the per-system 409, secrets never in a response, the system cascade,
  encryption at rest and key rotation** — pinned by `EnvironmentTest`/`EnvironmentEncryptionTest`
  (server) and `Environments.test.tsx`/`environmentForm.test.ts` (SPA).
- **Kafka targets** — no broker in the compose stack; the Kafka leg is tested with Testcontainers.
