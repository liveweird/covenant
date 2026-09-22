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

## Scenario: deleting an environment while its first filtered response is pending cannot restore the deleted row

1. Seed a uniquely named environment and its domain/system through the real API; the test owns all three.
2. Sign in and load the unfiltered Environments list, then enter the environment name filter.
3. Hold the first real filtered response containing that row while the previous page remains visible.
4. Delete the environment through its row menu and confirmation; verify the API no longer returns it.
5. Deliver the held pre-delete response after deletion succeeds.
   - *Expected*: the deleted row disappears without navigation or clearing the filter.
6. Release any held response and delete only the owned records through the API, even on failure.

## Not covered here (and why)

- **The shape rules, the per-system 409, secrets never in a response, the system cascade,
  encryption at rest and key rotation** — pinned by `EnvironmentTest`/`EnvironmentEncryptionTest`
  (server) and `Environments.test.tsx`/`environmentForm.test.ts` (SPA).
- **Kafka targets** — no broker in the compose stack; the Kafka leg is tested with Testcontainers.
