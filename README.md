# Covenant

Covenant is a **contract repository** for development teams: one place to publish, share, and
keep track of the contracts their integrations are built on.

A **contract** is the definition an integration happens against — an API, a message, a view:

- **synchronous APIs** — [OpenAPI](https://spec.openapis.org/oas/latest.html) (RESTful HTTP endpoints),
- **asynchronous, event-driven messaging** — [AsyncAPI](https://www.asyncapi.com/docs/reference)
  (Kafka topics, RabbitMQ queues, …) with **JSON Schema 2020-12** or **Avro** payload schemas
  (CloudEvents is the recommended envelope),
- **database read-only views** — [ODCS](https://bitol-io.github.io/open-data-contract-standard/),
  the Open Data Contract Standard.

No new standard is invented: a contract *version* is the standard document itself, stored
byte-exact. Around it Covenant adds what the standards leave to the team — **SemVer versions**,
a **lifecycle** (draft → proposed → active → deprecated → retired), **ownership** (a team, or an
individual user), a searchable **Domain → System → Contract** hierarchy, and **validation**:
syntax, schema, semantic and lint checks on every document, and **breaking-change detection**
against the active version (a non-major bump that breaks clients is a waivable blocking finding). Contracts live and are edited in Git; Covenant works like a catalog —
import (paste, file, or a Git blob URL), view and edit in a code editor, validate, diff versions,
export for the commit back.

## What's here today

- **The catalog** — Domain → System → Contract as a tree on the home page and as a filterable list
  (text, domain, system, type, lifecycle, owning team, only-with-errors); contracts of type
  **OpenAPI**, **AsyncAPI** or **ODCS** owned by a team or a person, with the write rule enforced
  server-side (owning team's members, the owning user, administrators) and ownership transfer
  reserved to administrators.
- **Versions** — the standard document itself, stored byte-exact; strict **SemVer** (a new number
  must be above the highest), the lifecycle DRAFT → PROPOSED → ACTIVE → DEPRECATED → RETIRED
  (PROPOSED may step back; the text locks from ACTIVE on; only a DRAFT deletes).
- **Checks** — on every save and live while typing: parse + type gate (hard, never waivable),
  swagger-parser for OpenAPI, offline JSON Schema validation for AsyncAPI 2.6/3.x and ODCS
  3.0–3.1 with the payload walk (JSON Schema 2020-12 meta-validation, Avro), then the **checker**
  sidecar's lint (Spectral) and AsyncAPI semantics — findings with severity, source, code and
  position, jump-to-line in the editor; soft errors save through an explicit "Save anyway".
- **Import, diff, export** — paste, upload or server-fetch a public URL (GitHub/GitLab blob links
  rewritten to raw); a dry run predicts the outcome; two versions compared line by line; any
  version downloadable as its file; a contract exportable with all its versions as JSON.
- **Registries** — domains, systems and flat teams with rosters, administrator-curated.
- accounts — JWT sign-in with a sliding refresh pair and a revocation blocklist, opt-in **email
  MFA**, self-service password reset, per-account lockout and per-IP rate limits,
- administration — user management with a one-time generated-password reveal, per-user feature
  flags,
- a bilingual (English/Polish) UI with light/dark themes and a ⌘K / Ctrl K command palette (pages,
  actions, and a server-side contract search),
- breaking-change detection against the active version (openapi-diff, `@asyncapi/diff`, a
  hand-written ODCS comparer) — a non-major bump that breaks clients is a waivable blocking finding,
- an **Environments** registry (per system: an HTTP base URL, a Kafka cluster, a read-only PostgreSQL database — passwords encrypted at rest, admin-curated) — the targets the **try-it** feature reaches: send a request to an OpenAPI operation through an environment and see the response measured against the contract (status, media type, headers, body against the response schema), publish a record onto an AsyncAPI channel's topic (contract writers only) or read its newest records, or read a bounded sample of an ODCS dataset over a read-only connection and see its columns measured against the declared properties — every observation measured against the contract as conformance findings; rate-limited (`TRY_RATE_LIMIT_PER_MINUTE`), redirects never followed, credentials typed per call and never stored,
- facet counts on the catalog filters — every option says how many contracts picking it would yield,
- in-app notifications: follow a contract and its every event — versions created, edited, published,
  synced, a breaking change waived, the owner changed — lands in your bell (never for your own acts),
- a per-contract history timeline, and a repo reference per version with sync-from-source
  (which side moved, a line diff, overwrite or a new version from the repo copy),
- every quality gate wired, locally and in CI.

Coming next (milestone 4): the contract reader — a structured, reader-optimised rendering of a version (the Swagger-UI equivalent) for OpenAPI, AsyncAPI and ODCS, beside the source view.

## The stack

- **Backend**: Kotlin + [Ktor](https://ktor.io) (Netty), JWT auth with refresh tokens and a
  server-side revocation blocklist, PostgreSQL with [Flyway](https://flywaydb.org) migrations
  and [Exposed](https://github.com/JetBrains/Exposed) (R2DBC), OpenTelemetry, RFC 7807
  problem-detail errors, Swagger UI at `/openapi` (development mode). Contract validation in the
  JVM: swagger-parser (OpenAPI), networknt JSON Schema validation against the vendored AsyncAPI
  and ODCS schemas, Apache Avro.
- **Checker sidecar**: Node 24 + TypeScript — [Spectral](https://stoplight.io/open-source/spectral)
  (OpenAPI and AsyncAPI rulesets) and [`@asyncapi/parser`](https://github.com/asyncapi/parser-js),
  on an internal network with no route out.
- **Frontend**: [Vite](https://vite.dev) + React 19 + TypeScript + [Mantine](https://mantine.dev),
  react-i18next (English + Polish), typed API client generated from the OpenAPI contract, a
  CodeMirror 6 editor for the documents (with the contracts feature).
- **Quality gates**: detekt (zero findings), Kover coverage floors, dependency-family alignment,
  ESLint + sonarjs, knip, Vitest coverage floors, runtime OpenAPI conformance in the server test
  suite, Playwright e2e with axe accessibility scans, and a GitHub Actions workflow running all of
  them on every push and pull request.

## Running the whole stack (one command)

```bash
docker compose up --build
```

Then open <http://localhost:8082> and sign in as `admin@covenant.local` / `changeme`.
Swagger UI: <http://localhost:8082/openapi>. Mailpit (reset and MFA email): <http://localhost:8027>.

Ports are chosen to coexist with [Lettuce](https://github.com/liveweird/lettuce) (8080 / 5432 /
5173 / 8025) and [Toadie](https://github.com/liveweird/toadie) (8081 / 5433 / 5174 / 8026) on the
same machine: the app is on **8082**, Postgres is host-mapped to **5434**, the Vite dev server uses
**5175**, Mailpit **8027**. All host ports bind to 127.0.0.1. The checker has no host port at all.

## Running on Kubernetes (local)

With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
docker build -t covenant-app:latest .
docker build -t covenant-checker:latest -f checker/Dockerfile .
kubectl create namespace covenant
# create the covenant-secrets Secret — see the header comment in k8s/secret.yaml
kubectl apply -f k8s/
```

## Local development

Four processes (the checker may run from compose or from source):

```bash
docker compose up postgres checker  # PostgreSQL on localhost:5434, the checker on the internal network
./gradlew :server:run               # API on localhost:8082
cd web && npm install --legacy-peer-deps && npm run dev   # SPA on localhost:5175 (proxies /api)
cd checker && npm ci && npm run dev # optional: the checker from source on :9090 (then CHECKER_URL=http://localhost:9090 for the API)
```

The local JDK is managed by [mise](https://mise.jdx.dev) (`mise.toml`, Temurin 21).

## Useful Gradle tasks

| Task | What it does |
| ---- | ------------ |
| `./gradlew build` | Compiles everything and runs every gate: detekt, tests (Testcontainers), Kover verify, dependency alignment |
| `./gradlew :server:run` | Runs the API against the compose Postgres |
| `./gradlew :server:test` | Server test suite (needs a Docker daemon for Testcontainers) |
| `./gradlew detekt` | Static analysis only |
| `./gradlew :server:checkDependencyAlignment` | One version per aligned dependency family on the runtime classpath |
| `./gradlew :server:installDist` | Builds the runnable distribution (used by the Docker image; never `buildFatJar`) |

Frontend: `cd web && npm run build | lint | test | test:coverage | knip | gen:api`.
Checker: `cd checker && npm run build | lint | knip | typecheck | test | test:coverage`.
E2E: `cd e2e && npm ci && npx playwright install chromium && npm test`.

## License

MIT — see [LICENSE](LICENSE).
