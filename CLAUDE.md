# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

Gradle wrapper is at `./gradlew` (use `gradlew.bat` on Windows). JDK 21 toolchain is required (auto-provisioned via foojay-resolver; the local dev JDK is pinned in `mise.toml`).

- Build everything: `./gradlew build`
- Run the server (Ktor + Netty on port 8082): `./gradlew :server:run`
- Run all tests: `./gradlew test`
- Run server tests only: `./gradlew :server:test` (needs a Docker daemon — Testcontainers; with OrbStack and no `/var/run/docker.sock`, export `DOCKER_HOST=unix://$HOME/.orbstack/run/docker.sock` first)
- Run a single test: `./gradlew :server:test --tests "ch.nokillswit.ServerTest.security headers are set on responses"`
- Static analysis (detekt, both Kotlin modules): `./gradlew detekt` — rides `check`/`build`, zero-findings gate (no baseline file). Rule tuning lives in `config/detekt/detekt.yml` ONLY, one commented override per deliberate repo idiom; never add an uncommented `@Suppress`.
- Dependency alignment: `./gradlew :server:checkDependencyAlignment` — one version per aligned family (Netty, OpenTelemetry, kotlin stdlib/reflect, Jackson 2) on the runtime classpath; rides `check`.
- Package the server for deployment: `./gradlew :server:installDist`. **Never use `:server:buildFatJar`** — the fat JAR breaks Flyway's `ServiceLoader` discovery and NPEs at startup.
- JVM memory flags are pre-tuned in `server/build.gradle.kts` (`applicationDefaultJvmArgs`) — the rationale is commented in place.
- **Run the whole stack with one command: `docker compose up --build`** (only Docker required). See "Running the full stack" below.
- Frontend: `cd web && npm install --legacy-peer-deps`, then `npm run dev|build|lint|test|test:coverage|knip|gen:api` (details in `web/CLAUDE.md`).
- Checker sidecar: `cd checker && npm ci`, then `npm run dev|build|lint|knip|typecheck|test|test:coverage` (details in `.claude/docs/checker.md`).
- E2E: `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `npm run typecheck` and `npm run check:scenarios`).
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (server, web, checker, e2e statics, image builds on `main`); the blackbox Playwright suite (`e2e.yml`) stays manual.

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8082` (sign in as `admin@covenant.local` / `changeme`); local dev is `docker compose up postgres checker` (Postgres on host port **5434**, the checker on the compose-internal network only) + `./gradlew :server:run` + `cd web && npm run dev` (Vite on **5175**, proxying `/api` to :8082). To run the checker outside Docker instead: `cd checker && npm run dev` (port 9090) and `CHECKER_URL=http://localhost:9090 ./gradlew :server:run`. The compose stack bundles **Mailpit** (`http://localhost:8027`) and wires the app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid Lettuce's 8080/5432/5173/8025 and Toadie's 8081/5433/5174/8026 so all three stacks can run side by side; host ports bind to 127.0.0.1 only. Kubernetes (OrbStack) deployment targets the dedicated `covenant` namespace — see `k8s/secret.yaml`'s header for the secret-creation command.

## Architecture

Covenant is a **contract repository**: development teams publish and share the contracts their integrations are built on — synchronous APIs (**OpenAPI**), asynchronous/event-driven messaging (**AsyncAPI**, with JSON Schema 2020-12 or Avro payload schemas), and read-only database views (**ODCS**, the Open Data Contract Standard) — each with **SemVer versions** and a **lifecycle** (DRAFT → PROPOSED → ACTIVE → DEPRECATED → RETIRED). No new specification standard is invented: a contract *version* is the standard document itself, stored **verbatim** (raw YAML or JSON text, never rewritten), with the metadata Covenant needs (type, version, lifecycle, ownership, extracted title/spec version, validation findings) in columns beside it. Contracts live and are edited in Git; Covenant imports them (paste / file / SSRF-guarded URL fetch), keeps the repo reference and syncs from it, displays and edits them in a code editor, **validates and lints** them (syntax → schema → semantic → lint → breaking changes against the highest ACTIVE version below the candidate — a non-major bump that breaks clients is the soft, waivable `BREAKING_WITHOUT_MAJOR_BUMP` error), **diffs** versions, and exports them for the commit back.

The catalog is hierarchical: **Domain → System → Contract → Version**. Domains and Systems are ADMIN-curated registries (a System belongs to one Domain); a Contract belongs to one System, has a type (`OPENAPI | ASYNCAPI | ODCS`) and an **owner** — a team, or (less often) an individual user. Every authenticated user reads everything; **writing a contract or its versions requires membership of the owning team, being the owning user, or ADMIN** (ownership transfer is ADMIN-only). Teams are flat (membership only — no management chain). Version rules are strict: numbers are unique per contract and a new version must exceed the highest existing one; the document text is editable only in DRAFT/PROPOSED (a DRAFT may be deleted); from ACTIVE on only the lifecycle moves. Validation is two-tier (Toadie's model): **HARD** findings — unparseable YAML/JSON, a document of the wrong type — are a `400` on every store path; **SOFT** findings (schema, semantic, lint) block a strict save but are waivable with `allowInvalid=true` (the editor's Save-anyway modal; import always waives). Findings are produced by the JVM (Jackson parse, swagger-parser for OpenAPI, networknt JSON Schema validation of AsyncAPI/ODCS against vendored official schemas, Apache Avro for payload schemas) merged with the **checker sidecar**'s verdicts (`checker/`: Spectral rulesets + `@asyncapi/parser`, reached only by the server over an internal network — an unreachable checker yields a report-only `CHECKER_UNAVAILABLE` finding, never a failed request). See `.claude/docs/contract-standards.md` for the formats and `.claude/docs/checker.md` for the sidecar.

**Implemented so far:** the full stack + tooling + auth surface — JWT login with a sliding refresh pair and a revocation blocklist, opt-in email MFA, self-service password reset, per-account lockout and per-IP rate limits, ADMIN-managed accounts and per-user feature flags, the synced per-user UI/email language (EN/PL), the React shell (nav model, command palette, theme, changelog), the checker service (Spectral + `@asyncapi/parser` behind `POST /check`), **flat teams** (ADMIN-curated registry with rosters — the ownership unit), the **Domain → System registries**, and the **contract catalog server side** (contracts with team/user ownership and the writer guard, SemVer versions with the lifecycle, the two-tier check pipeline — JVM parse/type gate/schema/semantic validators + the checker sidecar's lint — with the `allowInvalid` waiver, breaking-change detection against the ACTIVE baseline, the repo reference + sync-from-source (V12: the SPA fetches the reference through the guarded fetch, attributes the difference to a side against the stored baseline, and overwrites an editable version or seeds a new one), the raw-content download, import report-&-skip with a dry run, the SSRF-guarded URL fetch, export, the per-contract event log and the hierarchy tree) with its SPA (the Hierarchy tree at `/`, the Contracts list and pages with the history timeline, the CodeMirror editor with live findings and Save-anyway, import, diff, download, export), and every quality gate. The contract catalog continues feature by feature (contracts/versions → validators → import/diff/export) — each in the shape of the feature template below.

The architecture deliberately mirrors [Toadie](https://github.com/liveweird/toadie) and [Lettuce](https://github.com/liveweird/lettuce) — **port, don't reinvent**: when adding a capability one of them already has, port its implementation. Pointers: auth/users/MFA/flags/language/mail/paging → `toadie/server/src/main/kotlin/{auth,users,infra}`; the two-tier validation, `allowInvalid` waiver, import report-&-skip pipeline, sync baseline + line diff, SSRF-guarded URL fetch → `toadie/server/src/main/kotlin/catalog/` (+ `toadie/web/src/{hooks/useCatalogFileSave.ts,pages/ImportCatalogFiles.tsx,utils/yamlDiff.ts,components/YamlDiffView.tsx}`); per-record structural event history → `toadie/.../infra/db/EventLog.kt`; flat teams → `lettuce/server/src/main/kotlin/teams/` minus `ManagementChain.kt`/`TeamTree.kt`; AA-tested theme tokens → `lettuce/web/src/themeVariables.ts`; list-page building blocks → `toadie/web/src/{components,hooks}`.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules plus three standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`. Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`checker/`** — Node 24 + TypeScript sidecar wrapping the JavaScript contract tooling (Spectral, `@asyncapi/parser`) behind `POST /check`; the server is its only client.
- **`e2e/`** — Playwright blackbox suite against the compose stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (set in root `build.gradle.kts`). Dependency versions are centralized in `gradle/libs.versions.toml` (every pin carries its rationale); Ktor itself comes from a separate version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog` in `settings.gradle.kts`.

### The contract standards (the domain reference)

**`.claude/docs/contract-standards.md` is the local offline reference for the formats Covenant stores, validates and diffs** — OpenAPI 3.0 vs 3.1, AsyncAPI 2.6 vs 3.x, ODCS 3.x, JSON Schema 2020-12, Avro, SemVer 2.0 and how Covenant maps changes onto version bumps. Consult it when designing any contract feature instead of browsing; each section names the upstream source it snapshots — re-check upstream (and update the snapshot) when adding a validation rule. The official JSON Schemas the JVM validates against are vendored under `server/src/main/resources/schemas/` (its README records versions and origins; never hand-edit them).

### API guidelines (the authoritative API standard)

**`api-guidelines/API-GUIDELINES.md` is the single authoritative rulebook for API style** — document shape, URLs, versioning, list conventions, naming, data formats, status codes, errors, auth, caching, rate limiting, idempotency, security, and OpenAPI/conformance practice. Every rule has a stable ID (`API-LIST-002`); cite IDs when discussing API design. Validate spec changes with the `/api-review` skill (Spectral lint + LLM review checklist).

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The application is wired declaratively in `server/src/main/resources/application.yaml` under `ktor.application.modules` — each entry is a fully-qualified extension function on `Application` (e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins → infra (Mail → Flyway → Database → Bootstrap; Database is the composition root that publishes every service into `Application.attributes` via `AttributeKey`s) → feature route modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all). To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>` (no `ch/nokillswit` directory nesting — a deliberate idiom, protected by the `InvalidPackageDeclaration` detekt override).

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText/PasswordEmail (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       jsonArrayContains, orVanished) + EventLog.kt/JsonParams.kt (Lettuce's
│                       shared per-record audit-event machinery — the EventLogTable base the
│                       contract history will ride; no clone yet)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400)
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429/502)
├── auth/               POST /api/v1/login (+ the email-MFA branch and /login/mfa second
│                       step — MfaChallenges/MfaEmail), /refresh, /logout + the self-service
│                       POST /api/v1/password-reset (uniform 202, async send-before-store,
│                       PasswordResetThrottle) + token minting + password hashing/generation
│                       + LoginThrottle + the revoked-token blocklist
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + the per-user feature
│                       flags (Feature enum + PUT {id}/features, the V5 disabled-set model;
│                       MFA is the inverted-default login-scoped flag) + the per-user
│                       language (V1: PUT {id}/language, self-or-admin — the ONE synced
│                       UI+email language; Languages.kt is the SUPPORTED_LANGUAGES whitelist)
│                       + Validation.kt
├── contracts/          THE feature reference implementation (V9–V11): Contract.kt/ContractVersion.kt
│                       (DTOs, sanitizers, validateContractCreate, Ownership), ContractType.kt,
│                       Lifecycle.kt (the transition matrix + contentEditable/deletable/published),
│                       SemVer.kt (full 2.0 precedence), ContractAccess.kt (the owner-team/owner-user/
│                       ADMIN writer guard + requireOwnerAssignable), ContractService.kt (Contracts table;
│                       the list/tree joins, authorizeWrite reading team_members inside the tx, the
│                       published-versions 409 on delete, export), ContractVersionService.kt
│                       (ContractVersions table; SemVer > highest, the HARD/SOFT gate, transitions,
│                       recomputeLatest), ContractEvents.kt (the EventLog clone), Import.kt (report &
│                       skip, dry run = the same walk without the store), Tree.kt, UrlFetch.kt (Toadie's
│                       SSRF-guarded fetch), ContractRoutes.kt + ContractVersionRoutes.kt, and checks/ —
│                       Finding.kt (severity/source vocabulary, CheckReport), DocumentParser.kt (format
│                       detection, Jackson YAML with the alias/size caps, the type gate), Metadata.kt,
│                       OpenApiValidator/AsyncApiValidator/OdcsValidator.kt, BreakingChanges.kt (the baseline +
│                       the WARN/INFO/gate settlement) with OpenApiBreaking.kt (openapi-diff-core, 3.1 through the
│                       3.0 model) and OdcsBreaking.kt (hand-written; the ASYNCAPI facts come from the checker's
│                       @asyncapi/diff pass), VendoredSchemas.kt (offline
│                       networknt registries over resources/schemas), CheckerClient.kt (the sidecar
│                       client + its AttributeKey test seam), ChecksService.kt (the pipeline), Checks.kt
│                       (the module reading checker.url/token/timeoutMs)
├── teams/              flat teams (V6): Team.kt (DTOs + sanitizers + validateTeam*), TeamService.kt
│                       (Teams + the TeamMembers hard-delete join; paged list with name/memberId
│                       filters and active-member counts; roster read joining users; create with an
│                       initial roster; addMember/removeMember; activeTeamIdsOf — the contract writer
│                       guard's lookup), TeamRoutes.kt — GET /api/v1/teams (+ {id}) any authenticated,
│                       POST/PUT/DELETE + the members pair ADMIN only
├── domains/            the hierarchy's top level (V7): Domain.kt, DomainService.kt (paged list +
│                       listAll for the tree/pickers, active-system counts, the holds-systems 409 on
│                       delete), DomainRoutes.kt — /api/v1/domains, reads any authenticated, writes ADMIN
├── systems/            the hierarchy's middle level (V8): System.kt, SystemService.kt (joins the domain
│                       name; domainId filter; a PUT moves the system; the domain id checked inside the
│                       write's transaction), SystemRoutes.kt — /api/v1/systems, same authz split
```

**Feature template — copy `contracts/` (the full shape: ownership guard, sub-collection, checks) or `teams/` (a small registry)**: `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`) with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling `<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on every mutation, authorization BEFORE body decoding so 403 wins over 400), `<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`, soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`), spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages + `NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc + coverage-map line. Domain rules for contract features come from `.claude/docs/contract-standards.md`.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in the same commit. The server test suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`, default `-Dopenapi.conformance=fail`); the frontend derives its request/response types from it (`npm run gen:api` → committed `web/src/api/schema.ts` — regenerate in the same commit as a spec change). The file says `openapi: 3.1.0` but must use only 3.0-compatible constructs (the conformance harness relabels it in memory; `OpenApiSpecTest` guards this). The checker's own tiny contract lives in `checker/openapi.yaml`.

### Cross-cutting conventions

@.claude/docs/persistence.md
@.claude/docs/list-endpoints.md
@.claude/docs/security.md
@.claude/docs/authorization.md
@.claude/docs/observability.md
@.claude/docs/testing.md
@.claude/docs/contract-standards.md
@.claude/docs/checker.md

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n with EN/PL parity, the transport layer, theming — purple is the interactive accent only; red = blocking, orange = waived finding, teal = success).
