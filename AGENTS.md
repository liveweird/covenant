# Repository Guidelines

## Sources of Truth

This file is the Codex entry point. Before changing code, also read the relevant sections of
`CLAUDE.md`; when working under `web/`, read `web/CLAUDE.md` as well; when working under
`checker/`, read `.claude/docs/checker.md`. `CLAUDE.md` uses Claude's `@...` import syntax to
reference the cross-cutting conventions in `.claude/docs/` (persistence, list endpoints,
security, authorization, observability, testing, contract standards, the checker); Codex must
open the applicable files directly. Together those files contain the detailed, actively
maintained domain, security, persistence, UI, and testing conventions shared by the project. For
API work, `api-guidelines/API-GUIDELINES.md` is authoritative and its stable rule IDs should be
cited in reviews. If documentation and executable configuration disagree, the configuration and
code win; update the affected guidance in the same change.

Covenant deliberately mirrors [Toadie](https://github.com/liveweird/toadie) and
[Lettuce](https://github.com/liveweird/lettuce). The implemented surface today is the scaffold:
authentication/session handling (JWT pair, revocation blocklist, lockout, rate limits), email MFA
and password reset, admin-managed users and per-user feature flags, the synced user language,
shared paging, the React shell (nav model, command palette, theme, changelog), the checker
sidecar's HTTP contract, and every quality gate. The contract catalog — flat teams, the Domain →
System registries, contracts and their SemVer/lifecycle versions, validation and lint, import,
diff, export — lands feature by feature. When adding a capability one of the two siblings already
has, **port its implementation rather than inventing a new one**; `CLAUDE.md` lists where each
one lives.

`.claude/docs/contract-standards.md` is the local domain reference for the formats Covenant
stores and validates (OpenAPI, AsyncAPI, ODCS, JSON Schema, Avro, SemVer). Consult it before
designing contract behavior; re-check its linked upstream sources when introducing a new
validation rule. The official JSON Schemas are vendored under
`server/src/main/resources/schemas/` and are never hand-edited.

The playbooks in `.claude/skills/` are useful repository-local references even outside Claude:
`api-review` covers the two-pass OpenAPI review, `run-stack` covers packaging/deployment, and
`verify` covers browser verification and cleanup.

## Project Structure & Architecture

This is a Kotlin/Gradle backend plus three standalone npm workspaces:

- `core/` is Kotlin Multiplatform (currently JVM-targeted) and owns the shared OpenTelemetry SDK
  bootstrap.
- `server/` is the Kotlin/JVM Ktor application. Feature packages live directly under
  `server/src/main/kotlin/`: `auth`, `users`, `teams` (the flat-teams registry with rosters —
  the feature template), `domains` and `systems` (the Domain → System registries), and
  `contracts` (today only the SSRF-guarded URL fetch; the contract domain grows there — see
  `CLAUDE.md` "Package layout" for the planned shape). Cross-cutting wiring and policy live
  in `plugins/`, `audit/`, and `authz/`; database, mail, paging, and shared validation
  infrastructure live in `infra/`.
- `server/src/main/resources/application.yaml` declaratively registers application modules.
  `main.kt` only starts `EngineMain`; do not wire features from it. Module order matters because
  modules publish and consume Ktor application attributes.
- PostgreSQL is the only database. Flyway migrations under
  `server/src/main/resources/db/migration/` are the schema source of truth; Exposed over R2DBC is
  used for runtime queries. Never introduce runtime DDL such as `SchemaUtils.create`, and never
  edit an applied migration, including its comments: Flyway checksums are pinned by
  `MigrationChecksumTest`; add a new migration instead.
- `server/src/main/resources/openapi/documentation.yaml` is the hand-maintained API contract.
- `web/` is a standalone Vite + React 19 + TypeScript SPA. Gradle does not build it. Source is
  organized into `pages/`, `components/`, `hooks/`, `utils/`, `api/`, `changelog/`, and bilingual
  resources under `locales/{en,pl}/`.
- `checker/` is a standalone Node 24 + TypeScript service: Spectral and `@asyncapi/parser`
  behind `POST /check`, reached by the server only, on an internal network with no route out.
- Backend tests are in `server/src/test/kotlin/`, colocated frontend tests use `*.test.ts(x)`,
  checker tests are in `checker/test/`, and Playwright journeys are in `e2e/tests/*.spec.ts` with
  their design artifacts in `e2e/scenarios/*.md`.

Routing is feature-local. Cross-cutting Ktor wiring functions are named `configureXxx` and must be
registered in `application.yaml`. `plugins/Routing.kt` is only the final SPA/static-file catch-all.

## Build, Test, and Development Commands

- `docker compose up --build`: build and run PostgreSQL, the checker, the API, and the SPA at
  `http://localhost:8082` (sign in as `admin@covenant.local` / `changeme`); Mailpit captures
  reset and MFA email at `http://localhost:8027`.
- `docker compose up postgres checker`: start only the development database (host port **5434**,
  not 5432/5433 — Lettuce and Toadie may occupy those on the same machine) and the sidecar.
- `./gradlew build`: compile and verify the Gradle modules with the JDK 21 toolchain (the local
  dev JDK is pinned in `mise.toml`).
- `./gradlew :server:run`: start Ktor/Netty on port 8082.
- `./gradlew test` or `./gradlew :server:test`: run Kotlin tests; Docker is required for
  Testcontainers (OrbStack without `/var/run/docker.sock`: export
  `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock`).
- `./gradlew :server:test --tests "<fully-qualified test name>"`: run one backend test.
- `./gradlew detekt`: static analysis over `core` + `server` — zero-findings gate, no baseline.
- `./gradlew :server:checkDependencyAlignment`: one version per aligned dependency family.
- `cd web && npm run dev`: start Vite on port 5175, proxying `/api` to Ktor on :8082.
- `cd web && npm run build && npm run lint && npm test`: type-check, bundle, lint, and run Vitest.
- `cd web && npm run test:coverage`: run frontend coverage gates. `npm run knip`: dead-code gate.
- `cd web && npm run gen:api`: regenerate `web/src/api/schema.ts` from the OpenAPI contract.
- `cd checker && npm ci && npm run lint && npm run knip && npm run typecheck && npm run test:coverage && npm run build`:
  the checker's gates; `npm run dev` runs it on :9090.
- `cd e2e && npm test`: run Playwright against the full stack on port 8082;
  `npm run typecheck` and `npm run check:scenarios` are the Docker-free static gates.
- `.github/workflows/ci.yml` runs the server, web, checker, and e2e-static gates on every push/PR
  (and builds both images on `main`); `e2e.yml` is the manual blackbox suite.

For a clean frontend install, use `cd web && npm install --legacy-peer-deps`;
`openapi-typescript` declares a TypeScript 5 peer while the project uses TypeScript 6. Keep the
Gradle and npm toolchains disjoint.

Package deployments with `./gradlew :server:installDist`. Never use `buildFatJar`: merging Flyway
service descriptors breaks plugin discovery at runtime. JVM runtime flags are intentionally set in
`server/build.gradle.kts`; consult `.claude/skills/run-stack/SKILL.md` before changing them.

## API and Backend Conventions

Follow `api-guidelines/API-GUIDELINES.md` for resource naming, pagination, filtering, sorting,
errors, statuses, auth, and conformance. All error bodies are RFC 7807
`application/problem+json`. Keep authorization checks before resource-dependent validation so
callers cannot infer inaccessible state (403 wins over 400). List endpoints use the
`{items, page, pageSize, total}` envelope and the already-ported `infra/paging` machinery; copy
the users list implementation (and Toadie's catalog list, `toadie/server/src/main/kotlin/catalog/`)
rather than parsing pagination, filters, or sorting again (see `.claude/docs/list-endpoints.md`).

When an API changes, update all of the following in the same change:

1. Route/service behavior and focused tests.
2. `server/src/main/resources/openapi/documentation.yaml`.
3. The generated `web/src/api/schema.ts` via `npm run gen:api`.
4. API guideline conformance, using the Spectral ruleset and review checklist described in
   `.claude/skills/api-review/SKILL.md`.

Use `V<number>__description.sql` for migrations. Business entities follow the established
soft-delete convention (`marked_as_deleted`, active-row filtering on every read/count/mutation,
and partial unique indexes where deleted values may be reused); follow the detailed pattern in
`.claude/docs/persistence.md` rather than inventing a variant. Emit structured `audit(...)`
events for security-relevant mutations and denials, and never log passwords or tokens. Contract
product history (the structural event log) is separate from the security audit trail; contract
mutations extend both where applicable.

Contract content is readable by every authenticated user; writes are scoped to the owning team's
members, the owning user, or ADMIN. Validation has two classes. Unparseable documents and
type mismatches are hard `400`s that cannot be waived. Schema, semantic, and lint findings are
soft: an `ERROR` blocks a strict save, `WARN`/`INFO` never block, and create/replace may
explicitly use `allowInvalid=true`. The editor exposes that waiver as Save anyway; import always
waives soft findings, while the `/check` endpoints perform the same classification without
storing anything. Findings are the JVM's own syntax/schema/semantic verdicts merged with the
checker sidecar's lint/semantic ones; an unreachable checker degrades to a report-only
`CHECKER_UNAVAILABLE` finding. The document text is stored byte-exact and never rewritten.

Use four-space indentation, preserve existing package boundaries, PascalCase for Kotlin types,
and camelCase for functions and variables. Name backend test classes `*Test`.

## Frontend Conventions

Use two-space indentation, PascalCase for React components, and the existing shared
components/hooks instead of cloning transport, error-mapping, or session logic.
The design system is owned by `web/src/theme.ts`, `web/src/themeVariables.ts` (AA-tested colour
tokens, guarded by `theme.test.ts`), and `web/src/theme.module.css`: the brand purple
(`covenant` tuple) is reserved for primary actions, active navigation, and focus; don't
reintroduce stock-blue actions or stock-green success states (success is teal, a blocking error
is red, a waived finding is orange). Keep accessibility roles, labels, and semantic tables stable
because tests and Playwright use them as contracts.

All user-facing strings must use react-i18next. Keep English and Polish resources in parity
(enforced by `locales/parity.test.ts`); Polish uses inclusive slash forms. Errors render inline
as red Alerts; follow `web/CLAUDE.md` for the exact transport, i18n, and theming patterns.

Pages are lazy and use shared `PageHeader` chrome; navigation is defined once in
`utils/navigation.ts` for the sidebar, user menu, and command palette. User creation/reset
passwords are generated client-side and revealed exactly once; the server never returns plaintext
passwords. The selected UI language is also stored on the user and drives server-composed email.

`web/src/changelog/version.ts` (`APP_VERSION`) is the sole source of the displayed app version;
the Gradle snapshot version is unrelated. A release adds the newest bilingual markdown entry to
`web/src/changelog/entries.ts` and bumps `APP_VERSION` in the same change; tests pin their parity.

## Testing and Verification

Use Kotlin Test/Ktor Test Host, Vitest with Testing Library (web and checker), and Playwright for
cross-stack journeys. Add focused regression coverage for behavioral changes. Backend tests boot
PostgreSQL through Testcontainers, apply every Flyway migration, and include the V3 seed admin; use
unique markers (`uniqueEmail(...)`) instead of asserting global counts.

Every `/api/` interaction made through the shared backend test clients is checked against OpenAPI.
Prefer `jsonClient()`/`authedClient()` so tests do not bypass conformance validation. `check`
enforces the Kover floors in `server/build.gradle.kts` and the Vitest floors in
`web/vite.config.ts` / `checker/vitest.config.ts`. Re-measure and raise floors as coverage
improves; do not lower them to accommodate new code. Any test-local Mantine provider must set
`env="test"` so popovers and selects work under happy-dom.

A new or behaviorally changed e2e test lands with its scenario file in `e2e/scenarios/` and its
coverage-map line in `e2e/README.md` in the same commit (`npm run check:scenarios` enforces the
parity); scenario headings and Playwright test titles must match exactly. For nontrivial cross-stack
behavior, verify through the SPA using the workflow in `.claude/skills/verify/SKILL.md`, and clean
up any records created in the development database.

## Commit, Documentation, and Security

Use Conventional Commit subjects such as `feat:`, `fix:`, `fix(e2e):`, and `docs:`. PRs should
explain behavior and risk, list verification commands, link issues, and include screenshots for UI
changes. Keep migrations, API contract, generated schema, tests, and both translations
synchronized when applicable.

Never commit production JWT, database, or checker secrets. Committed `changeme` values and
development keys are burned demo credentials; production mode deliberately refuses them (the JWT
fail-closed check in `plugins/Security.kt` and the seed-password check in `infra/db/Bootstrap.kt`).
Mail transport is also fail-closed: production refuses the `log` transport, and SMTP with a blank
host fails in every mode. The compose demo uses SMTP through Mailpit; the image defaults to
disabled mail, where reset and MFA-dependent flows return 503. The checker is reachable only from
the app (compose internal network / k8s NetworkPolicy) and never fetches anything itself.
