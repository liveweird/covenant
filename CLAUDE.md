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
- E2E: `cd e2e && npm ci && npx playwright install chromium && npm test` (plus `npm run lint`, `npm run knip`, `npm run typecheck` and `npm run check:scenarios`).
- CI: `.github/workflows/ci.yml` re-runs every gate above on push/PR (server — incl. the OpenAPI coverage gate, a HIGH/CRITICAL Gradle lockfile vulnerability scan, web — incl. the spec → `schema.ts` drift check, checker, e2e statics, image builds on `master`); the blackbox Playwright suite (`e2e.yml`) runs nightly and on demand. Dependabot (`.github/dependabot.yml`) checks every workspace, Actions and container manifests weekly; `.claude/docs/dependencies.md` describes grouping, compatibility pins and runtime verification.

## Running the full stack

`docker compose up --build` serves everything at `http://localhost:8082` (sign in as `admin@covenant.local` / `changeme`); local dev is `docker compose up postgres mailpit` (Postgres on host port **5434**) + `cd checker && npm run dev` (checker on host port **9090**) + `CHECKER_URL=http://localhost:9090 ./gradlew :server:run` + `cd web && npm run dev` (Vite on **5175**, proxying `/api` to :8082), each in its own terminal. The Compose checker is internal-only and has no host port; a host-run JVM needs the source checker. The compose stack bundles **Mailpit** (`http://localhost:8027`) and wires the app's password-reset and MFA email to it (`MAIL_TRANSPORT=smtp`). Ports deliberately avoid Lettuce's 8080/5432/5173/8025 and Toadie's 8081/5433/5174/8026 so all three stacks can run side by side; host ports bind to 127.0.0.1 only. Kubernetes (OrbStack) deployment targets the dedicated `covenant` namespace — see `k8s/secret.yaml`'s header for the secret-creation command.

## Architecture

Covenant is a **contract repository**: development teams publish and share the contracts their integrations are built on — synchronous APIs (**OpenAPI**), asynchronous/event-driven messaging (**AsyncAPI**, with JSON Schema 2020-12 or Avro payload schemas), and read-only database views (**ODCS**, the Open Data Contract Standard) — each with **SemVer versions** and a **lifecycle** (DRAFT → PROPOSED → ACTIVE → DEPRECATED → RETIRED). No new specification standard is invented: a contract *version* is the standard document itself, stored **verbatim** (raw YAML or JSON text, never rewritten), with the metadata Covenant needs (type, version, lifecycle, ownership, extracted title/spec version, validation findings) in columns beside it. Contracts live and are edited in Git; Covenant imports them (paste / file / SSRF-guarded URL fetch), keeps the repo reference and syncs from it, displays and edits them in a code editor, **validates and lints** them (syntax → schema → semantic → lint → breaking changes against the highest ACTIVE or DEPRECATED predecessor below the candidate, within its major where available — a non-major bump that breaks clients is the soft, waivable `BREAKING_WITHOUT_MAJOR_BUMP` error), **diffs** versions, and exports them for the commit back.

The catalog is hierarchical: **Domain → System → Contract → Version**. Domains and Systems are ADMIN-curated registries (a System belongs to one Domain); a Contract belongs to one System, has a type (`OPENAPI | ASYNCAPI | ODCS`) and an **owner** — a team, or (less often) an individual user. Every authenticated user reads everything; **writing a contract or its versions requires membership of the owning team, being the owning user, or ADMIN** (ownership transfer is ADMIN-only). Teams are flat (membership only — no management chain). Version rules are strict: numbers are unique per contract by SemVer precedence (ignoring build metadata), and older-version backports may be added after higher versions; the document text is editable only in DRAFT/PROPOSED (a DRAFT may be deleted); from ACTIVE on only the lifecycle moves. Validation is two-tier (Toadie's model): **HARD** findings — unparseable YAML/JSON or a document of the wrong type — are a `400` on every store path; **SOFT** findings (schema, semantic, lint) block a strict save but are waivable with `allowInvalid=true` (the editor's Save-anyway modal; import always waives). Unsupported AsyncAPI/ODCS versions remain soft SCHEMA errors in API v1 for compatibility: they cannot be validated completely but may be explicitly waived. Findings are produced by the JVM (Jackson parse, swagger-parser for OpenAPI, networknt JSON Schema validation of AsyncAPI/ODCS against vendored official schemas, Apache Avro for payload schemas) merged with the **checker sidecar**'s verdicts (`checker/`: Spectral rulesets + `@asyncapi/parser`, reached only by the server over an internal network — an unreachable checker yields a report-only `CHECKER_UNAVAILABLE` finding, never a failed request). See `.claude/docs/contract-standards.md` for the formats and `.claude/docs/checker.md` for the sidecar.

The architecture deliberately mirrors [Toadie](https://github.com/liveweird/toadie) and [Lettuce](https://github.com/liveweird/lettuce) — **port, don't reinvent**: when adding a capability one of them already has, port its implementation. Pointers: auth/users/MFA/flags/language/mail/paging → `toadie/server/src/main/kotlin/{auth,users,infra}`; the two-tier validation, `allowInvalid` waiver, import report-&-skip pipeline, sync baseline + line diff, SSRF-guarded URL fetch → `toadie/server/src/main/kotlin/catalog/` (+ `toadie/web/src/{hooks/useCatalogFileSave.ts,pages/ImportCatalogFiles.tsx,utils/yamlDiff.ts,components/YamlDiffView.tsx}`); per-record structural event history → `toadie/.../infra/db/EventLog.kt`; flat teams → `lettuce/server/src/main/kotlin/teams/` minus `ManagementChain.kt`/`TeamTree.kt`; AA-tested theme tokens → `lettuce/web/src/themeVariables.ts`; list-page building blocks → `toadie/web/src/{components,hooks}`.

Multi-module Gradle build (Kotlin DSL) defined in `settings.gradle.kts` with two Kotlin modules plus three standalone npm workspaces (Gradle never touches them):

- **`core`** — Kotlin Multiplatform (JVM target only currently). Shared code consumed by `server`. Holds the OpenTelemetry SDK bootstrap (`getOpenTelemetry(serviceName)`).
- **`server`** — Kotlin/JVM. The Ktor application. Depends on `core`.
- **`web/`** — Vite + React + TypeScript SPA that consumes the server's HTTP API.
- **`checker/`** — Node 24 + TypeScript sidecar wrapping the JavaScript contract tooling (Spectral, `@asyncapi/parser`) behind `POST /check`; the server is its only client.
- **`e2e/`** — Playwright blackbox suite against the compose stack.

Group is `ch.nokillswit`, version `1.0.0-SNAPSHOT` (set in root `build.gradle.kts`). Dependency versions are centralized in `gradle/libs.versions.toml` (every pin carries its rationale); Ktor itself comes from a separate version catalog (`ktorLibs`) loaded from `io.ktor:ktor-version-catalog` in `settings.gradle.kts`.

### Parallel release lines (0.9.0)

`contracts/ReleaseLine.kt` and `ReleaseLineService.kt` add per-contract major-derived lines,
with the V16 migration, independent support metadata, and automatic or pinned stable ACTIVE
recommendations. Versions can be added out of order for backports. The global highest catalog
version is distinct from a line recommendation; defaults use UNSPECIFIED support. Read
`.claude/docs/release-lines.md` for baseline selection, policy semantics, concurrency, API,
permissions and rollout rules. The contract SPA exposes line summaries, policy editing,
line-filtered versions and line-aware creation.

### Toadie usage integration (0.10.0)

API and ODCS dataset mappings use separate connections to the same Toadie instance. The
Advanced mapping presets and explicit multi-dataset linking are documented in
`.claude/docs/toadie-integration.md`; optional adoption declarations are read only when an
ADMIN enables the separate mapping. See `.claude/docs/toadie-adoption.md` for the operating
rules.

The `toadie/` server package reads Toadie’s existing Port GraphQL API through ADMIN-curated
connections with encrypted machine keys. Contract writers link one or more remote APIs or datasets;
read-only usage shows provider/consumer services, systems and teams. Periodic/manual refresh
preserves the last successful observation on failure. Since 0.16.0, Toadie 2.12+ is required:
full scans compare revisions across every page with one bounded restart, scheduled refreshes
reconfirm unchanged revisions without rescanning, and manual refreshes always scan fully.
It never synchronizes authorization or
infers runtime version adoption: runtime use remains unknown, while explicitly declared API
major lines and dataset contract versions are exposed as separate source declarations. See
`.claude/docs/toadie-integration.md` and `.claude/docs/toadie-adoption.md` for mapping, transport,
concurrency, cache semantics and deployment. The SPA adds Toadie connection administration
and a Usage from Toadie panel on contract details.

### Declared adoption reader (1.1.0)

An opt-in per-connection reader exposes Toadie's API major-line and dataset contract-version
declarations in lifecycle impact views and migration reports. These declarations retain their
source values and provenance; they do not prove runtime use, change architecture counts or make
retirement safe. Missing declarations remain unknown. See `.claude/docs/toadie-adoption.md`.

### Registry synchronization from Toadie (1.0.0)

An optional per-connection mapping lets ADMIN preview and import or explicitly link selected
Port domains, systems and teams. Linked names and configured descriptions follow Toadie;
system placement follows mapped domains with an explicit fallback for domainless systems.
Refresh reconciles existing links and exposes conflicts or missing sources while preserving
local records, contracts and Environments. Team rosters and all permissions remain local.
V23 adds registry configuration, snapshot projections and source bindings. Read
`.claude/docs/toadie-registry-sync.md` for projection, preview concurrency, locking and ownership.

### Shareable migration and impact reports (0.15.0)

The release-line migration-report endpoint returns the saved plan and complete cached declared
usage for one contract in a consistent local read. The SPA exports a localized Markdown file
from the existing impact dialog. It does not refresh Toadie or infer version adoption. See
`.claude/docs/release-lines.md` for snapshot semantics, limitations and permissions.

### The contract standards (the domain reference)

**`.claude/docs/contract-standards.md` is the local offline reference for the formats Covenant stores, validates and diffs** — OpenAPI 3.0 vs 3.1, AsyncAPI 2.6 vs 3.x, ODCS 3.x, JSON Schema 2020-12, Avro, SemVer 2.0 and how Covenant maps changes onto version bumps. Consult it when designing any contract feature instead of browsing; each section names the upstream source it snapshots — re-check upstream (and update the snapshot) when adding a validation rule. The official JSON Schemas the JVM validates against are vendored under `server/src/main/resources/schemas/` (its README records versions and origins; never hand-edit them).

### API guidelines (the authoritative API standard)

**`api-guidelines/API-GUIDELINES.md` is the single authoritative rulebook for API style** — document shape, URLs, versioning, list conventions, naming, data formats, status codes, errors, auth, caching, rate limiting, idempotency, security, and OpenAPI/conformance practice. Every rule has a stable ID (`API-LIST-002`); cite IDs when discussing API design. Validate spec changes with the `/api-review` skill (Spectral lint + LLM review checklist).

### Server bootstrap model

`server/src/main/kotlin/main.kt` just delegates to `io.ktor.server.netty.EngineMain`. The application is wired declaratively in `server/src/main/resources/application.yaml` under `ktor.application.modules` — each entry is a fully-qualified extension function on `Application` (e.g. `ch.nokillswit.plugins.HttpKt.configureHttp`). **Module order is load-bearing**: plugins → infra (Mail → Crypto → Flyway → Checks → Database → Bootstrap → Health; Database is the composition root that publishes every service into `Application.attributes` via `AttributeKey`s) → feature route modules → `RoutingKt.configureRouting` strictly last (the SPA catch-all). To add a cross-cutting concern, create a `configureXxx()` extension under `plugins/` and register it in `application.yaml`; do not call it from `main.kt`. There is no DI framework — services travel via `attributes`.

### Package layout

Source files sit flat under `server/src/main/kotlin/<area>/` but declare `package ch.nokillswit.<area>` (no `ch/nokillswit` directory nesting — a deliberate idiom, protected by the `InvalidPackageDeclaration` detekt override).

The annotated tree of every area lives in **`.claude/docs/package-layout.md`** — read it when
you need to find where a concern lives, or before adding a new area.

**Feature template — copy `contracts/` (the full shape: ownership guard, sub-collection, checks) or `teams/` (a small registry)**: `<feature>/<Entity>.kt` (request/response DTOs + `toResponse`) with the `validateX` free function enforced by route AND service (in the DTO file, or a sibling `<Entity>Validation.kt` once the rules outgrow it), `<Entity>Routes.kt` (`@Resource` typed routes under `/api/v1/...` + `configureXRoutes()` reading services from `attributes`, `audit(...)` on every mutation, authorization BEFORE body decoding so 403 wins over 400), `<Entity>Service.kt` (Exposed `object` table nested inside the service, `suspendTransaction`, soft-delete via `marked_as_deleted` + partial unique indexes, list = count + rows on one predicate), a `V<n>__description.sql` migration (+ its checksum pin in `MigrationChecksumTest`), spec paths in `openapi/documentation.yaml`, `cd web && npm run gen:api` (same commit), lazy pages + `NAV_SECTIONS` entries (`web/src/utils/navigation.ts`), and an e2e spec + scenario doc + coverage-map line. Domain rules for contract features come from `.claude/docs/contract-standards.md`.

### The OpenAPI contract

`server/src/main/resources/openapi/documentation.yaml` is hand-maintained and authoritative: every endpoint change edits it in the same commit. The server test suite validates every test-client `/api/` interaction against it (`OpenApiConformance.kt`, default `-Dopenapi.conformance=fail`); the frontend derives its request/response types from it (`npm run gen:api` → committed `web/src/api/schema.ts` — regenerate in the same commit as a spec change). The file says `openapi: 3.1.0` but must use only 3.0-compatible constructs (the conformance harness relabels it in memory; `OpenApiSpecTest` guards this). The checker's own tiny contract lives in `checker/openapi.yaml`.

### Invariants (always apply — do not wait for a reference doc to say so)

- **An applied Flyway migration's bytes are immutable, comments included.** Never edit a `V*.sql`
  that exists; add a new one and its `MigrationChecksumTest` manifest line. A red checksum pin
  means REVERT the edit, never update the pinned value.
- **Never use `:server:buildFatJar`** — it breaks Flyway's `ServiceLoader` discovery and NPEs at
  startup. Package with `:server:installDist`.
- **Never log a secret, a document's text, or a payload/cell/header value.** The audit trail
  carries ids, hosts, counts and outcomes only; contract history is structural, never rendered.
- **Never filter or sort on an encrypted column** (ciphertext carries no order or equality), and
  never remove a service from `encryptedAtRestServices()` — a rotation would strand its rows.
- **Never weaken the CSP `script-src`** in `plugins/SecurityHeaders.kt`, and keep the SPA free of
  `dangerouslySetInnerHTML`/`eval` sinks.
- **Never hand-edit the vendored schemas** under `server/src/main/resources/schemas/`.
- **Never auto-merge a breaking dependency migration** to clear an advisory; the compatibility
  boundaries in `dependencies.md` are load-bearing.
- Authorization runs BEFORE body decoding (403 wins over 400), and every mutation emits an
  `audit(...)` event in the same change that adds it.

### Cross-cutting conventions (read on demand)

These are reference documents, not always-loaded context. Read the one whose trigger matches the
task before designing or changing that area; they are authoritative where they apply.

| Read this | When |
| --- | --- |
| `.claude/docs/persistence.md` | Touching the database: tables, migrations, soft delete, locking, transactions |
| `.claude/docs/list-endpoints.md` | Adding or changing any list, facet or paged sub-collection |
| `.claude/docs/security.md` | Auth, outbound calls, secrets, crypto, rate limits, headers, the try-it/observe trust boundary |
| `.claude/docs/authorization.md` | Roles, guards, ownership, the writer guard, 403-vs-404 policy |
| `.claude/docs/observability.md` | Adding an audit event, history event, notification, log or probe |
| `.claude/docs/testing.md` | Writing any test, or touching a gate, fixture or coverage floor |
| `.claude/docs/dependencies.md` | Any dependency, image or runtime version change |
| `.claude/docs/app-releases.md` | Publishing a version, tag or GitHub release |
| `.claude/docs/contract-standards.md` | Designing ANY contract feature — the offline format reference (OpenAPI, AsyncAPI, ODCS, JSON Schema, Avro, SemVer) |
| `.claude/docs/release-lines.md` | Release lines, support policy, lifecycle plans, the lifecycle overview, migration reports |
| `.claude/docs/checker.md` | The Node checker sidecar, Spectral/AsyncAPI engines, its offline posture |
| `.claude/docs/package-layout.md` | Finding where a server concern lives, or adding a new area |
| `.claude/docs/toadie-integration.md` | The Toadie connector: connections, mapping, refresh, transport |
| `.claude/docs/toadie-adoption.md` | Declared adoption declarations from Toadie |
| `.claude/docs/toadie-registry-sync.md` | Registry synchronization of domains, systems and teams |
| `.claude/docs/version-reviews.md` | Version review rounds, entries, content revisions |
| `.claude/docs/review-inbox.md` | The review inbox at `/reviews` |

### Frontend (`web/`)

See `web/CLAUDE.md` for the frontend conventions (flat directories, co-located tests, typed i18n with EN/PL parity, the transport layer, theming — purple is the interactive accent only; red = blocking, orange = waived finding, teal = success).

### Planned lifecycle changes (0.11.0)

Release-line policy includes advisory deprecation/support dates, a replacement contract/major,
and migration guidance. `ReleaseLineReminders.kt` provides the UTC hourly worker and atomic,
recipient-scoped deduplication; V18 adds the fields and notification key. No automatic retirement
or Toadie mutation occurs. The SPA requires a consumer-impact review for retirement/end of
support. Rules, failure semantics and rollout notes live in `.claude/docs/release-lines.md`.

### Lifecycle overview (0.12.0)

`contracts/LifecycleOverview*.kt` supplies the catalog-wide paged read and attention summary;
`toadie/ToadieOverview.kt` batches cached usage counts and shares freshness classification with
the existing detail view. `web/src/pages/LifecycleOverview.tsx` owns `/lifecycle`, reuses policy
and impact dialogs, and uses the server's current writer permission. No migration or Toadie
change is required. UTC windows, empty/EOL handling, completeness and filtering semantics are
documented in `.claude/docs/release-lines.md`.
