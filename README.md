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
against the relevant published predecessor (a non-major bump that breaks clients is a waivable blocking finding). Contracts live and are edited in Git; Covenant works like a catalog —
import (paste, file, or a Git blob URL), view and edit in a code editor, validate, diff versions,
export for the commit back.

## What's here today

- **The catalog** — Domain → System → Contract as a tree on the home page and as a filterable list
  (text, domain, system, type, lifecycle, owning team, only-with-errors); contracts of type
  **OpenAPI**, **AsyncAPI** or **ODCS** owned by a team or a person, with the write rule enforced
  server-side (owning team's members, the owning user, administrators) and ownership transfer
  reserved to administrators.
- **Versions** — the standard document itself, stored byte-exact; strict **SemVer** with unique
  precedence and backports permitted after higher versions, the lifecycle DRAFT → PROPOSED → ACTIVE → DEPRECATED → RETIRED
  (PROPOSED may step back; the text locks from ACTIVE on; only a DRAFT deletes).
- **Parallel release lines** — maintain `1.x` alongside `2.x`, including older-minor backports.
  Each major line has its own support status, optional support-end date and policy notes, and
  an automatic or explicitly pinned stable recommendation. Support metadata does not change
  version lifecycle or claim deployment; see [the release-line rules](.claude/docs/release-lines.md).
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
- breaking-change detection against the relevant published predecessor (openapi-diff, `@asyncapi/diff`,
  Apache Avro reader/writer compatibility for AsyncAPI payloads, and a hand-written ODCS comparer) —
  a non-major bump that breaks clients is a waivable blocking finding,
- an **Environments** registry (per system: an HTTP base URL, a Kafka cluster, a read-only PostgreSQL database — passwords encrypted at rest, admin-curated) — the targets the **try-it** feature reaches: send a request to an OpenAPI operation through an environment and see the response measured against the contract (status, media type, headers, body against the response schema), publish a record onto an AsyncAPI channel's topic (contract writers only) or read its newest records, or read a bounded sample of an ODCS dataset over a read-only connection and see its columns measured against the declared properties — every observation measured against the contract as conformance findings; rate-limited (`TRY_RATE_LIMIT_PER_MINUTE`), redirects never followed, credentials typed per call and never stored,
- facet counts on the catalog filters — every option says how many contracts picking it would yield,
- in-app notifications: follow a contract and its every event — versions created, edited, published,
  synced, a breaking change waived, the owner changed — lands in your bell (never for your own acts),
- a per-contract history timeline, and a repo reference per version with sync-from-source
  (which side moved, a line diff, overwrite or a new version from the repo copy),
- the **contract reader** — a structured, reader-optimised rendering of any version (the Swagger-UI equivalent, for OpenAPI, AsyncAPI and ODCS alike): operations by tag with parameters, bodies and responses; channels, operations and messages; datasets with their columns, quality rules, servers and service levels — schemas as navigable trees with references resolved, recursion and unresolvable references marked, a table of contents, and findings that jump to the element they concern; a Source toggle keeps the raw document one click away,
- the **Errors report** across stored findings, a two-way **compatibility report** between versions, and **inference** from HTTP exchanges, messages or database descriptions into a draft; the Observe flow captures samples through Environments,
- every quality gate wired, locally and in CI.

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
  suite, and Playwright e2e with axe accessibility scans. Pushes to `main` and pull requests run
  the server/web/checker gates, sample-loader tests and E2E static/setup checks; the full browser
  suite runs nightly and on demand.

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

## Sample contracts

[`samples/contracts/`](samples/contracts/README.md) contains reusable OpenAPI, AsyncAPI, and
ODCS documents: clean examples, intentional validation errors, and an OpenAPI lint-warning
example. Load them into a running local instance with Python 3 (standard library only):

```bash
python3 samples/contracts/load.py --base-url http://localhost:8082
```

The loader prompts for the local administrator password, checks every document before
creating catalog data, and skips unchanged samples on repeat runs. Use `--check-only` to
validate the files without creating catalog records. See the sample README for expected
findings and how to reload after editing or deleting samples.

## Running on Kubernetes (local)

The app uses a single replica with `Recreate` updates: the old pod stops before the new one
starts, so upgrades briefly interrupt service. This prevents older password-update code from
running alongside the credential-revision-aware server introduced in 0.14.1.


With a local cluster that shares the Docker image store (e.g. OrbStack):

```bash
COVENANT_IMAGE_TAG="dev-$(git rev-parse --short=12 HEAD)-$(date +%s)"
docker build -t "covenant-app:${COVENANT_IMAGE_TAG}" .
docker build -t "covenant-checker:${COVENANT_IMAGE_TAG}" -f checker/Dockerfile .
kubectl create namespace covenant
# create the covenant-secrets Secret — see the header comment in k8s/secret.yaml
./k8s/apply-local.sh "$COVENANT_IMAGE_TAG"
```

The helper renders both deployment images with the selected build tag and excludes the Secret
template. Use a new tag for each rebuild; do not apply `k8s/` directly, since the checked-in
image names are placeholders. Existing installations already have the namespace and Secret.

## Local development

Run each long-lived process in a separate terminal from the repository root:

```bash
docker compose up postgres mailpit   # database on :5434 and local mail on :8027
(cd checker && SCARF_ANALYTICS=false npm ci && npm run dev)  # checker on :9090
CHECKER_URL=http://localhost:9090 ./gradlew :server:run       # API on :8082
(cd web && npm install --legacy-peer-deps && npm run dev)    # SPA on :5175
```

The Compose checker is isolated and has no host port; a host-run JVM uses the source checker
above. If CHECKER_URL is omitted, the server runs with incomplete checks marked
`CHECKER_UNAVAILABLE`. The full Compose app reaches its own checker internally.

The local JDK and Node runtime are pinned in `mise.toml` (Temurin 21 and Node 24 LTS).
Run `mise install` and use `mise exec -- <command>` or activate mise in your shell.
See [dependency maintenance](.claude/docs/dependencies.md) for compatibility pins,
automated updates, and runtime verification.

## Configuration (environment variables)

`server/src/main/resources/application.yaml` is the authoritative reference — every setting there is a `$VAR:default` pair, and the table below is generated from it (defaults as shipped; the Docker image additionally sets `KTOR_DEVELOPMENT=false`, `MAIL_TRANSPORT=disabled` and `WEB_STATIC_DIR=/app/web`). Production mode refuses the burned demo keys and the `log` mail transport at startup (`.claude/docs/security.md`).

| Variable | Default | Purpose |
|---|---|---|
| `KTOR_DEVELOPMENT` | `true` | Development mode (`true`) vs production mode — HSTS/HTTPS redirect and the fail-closed startup checks. The image ships `false`. |
| `LOGIN_LOCKOUT_THRESHOLD` | `5` | Consecutive failures per account before `/login` answers 429. |
| `LOGIN_LOCKOUT_DURATION_SECONDS` | `900` | How long a locked account stays locked. |
| `LOGIN_LOCKOUT_MAX_TRACKED` | `10000` | Maximum in-memory login identities; new identities receive 429 at capacity while active counters and locks remain. |
| `LOGIN_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP login bucket; blank follows the mode (10 prod / 1000 dev). |
| `REFRESH_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP refresh bucket (blank = 30). |
| `PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP reset bucket; blank follows the mode (5 prod / 100 dev). |
| `TRY_RATE_LIMIT_PER_MINUTE` | *(blank)* | Per-IP bucket shared by the four try-it POSTs (blank = 60). |
| `PASSWORD_RESET_MIN_INTERVAL_SECONDS` | `60` | One reset request per submitted email per interval. |
| `PASSWORD_RESET_MAX_TRACKED` | `10000` | Maximum in-memory reset cooldowns; new identities receive 429 at capacity. |
| `MFA_CODE_TTL_SECONDS` | `300` | Lifetime of an emailed MFA code. |
| `MFA_MAX_ATTEMPTS` | `5` | Wrong-code attempts before a challenge dies. |
| `MFA_MAX_TRACKED` | `10000` | Maximum pending email-MFA challenges; new issuance receives 429 at capacity. |
| `DATA_ENCRYPTION_KEY` | *(dev key, burned)* | AES-256-GCM key (64 hex) for the environments' stored credentials — the dev default is burned, production refuses it. Back it up apart from the database. |
| `DATA_ENCRYPTION_KEY_PREVIOUS` | *(blank)* | Decrypt-only fallback during a key rotation (boot once, then remove). |
| `SECURITY_CSRF_ENABLED` | `false` | CSRF plugin gate — off (bearer JWT, no cookies). |
| `JWT_SECRET` | `secret` | HMAC key for the access/refresh pair — production requires a private 64-hex key (`openssl rand -hex 32`); the placeholders and compose demo key are burned. |
| `JWT_ISSUER` | `http://0.0.0.0:8082/` | The `iss` claim the verifier requires. |
| `JWT_AUDIENCE` | `covenant-api` | The `aud` claim the verifier requires. |
| `JWT_REALM` | `covenant-api` | The `WWW-Authenticate` realm. |
| `JWT_ACCESS_EXPIRES_IN_SECONDS` | `900` | Access-token lifetime (the API bearer). |
| `JWT_REFRESH_EXPIRES_IN_SECONDS` | `3600` | Refresh-token lifetime — the idle-session window. |
| `ADMIN_INITIAL_PASSWORD` | *(blank)* | Rotates the V3 seed admin's `changeme` at startup while it still carries the seed hash; production rejects known placeholders and passwords outside the ordinary account limits. |
| `HTTP_BEHIND_PROXY` | `false` | Honour `X-Forwarded-*` from a TLS-terminating proxy (rate-limit keys, HTTPS redirect). |
| `CORS_ALLOWED_HOSTS` | *(blank)* | Comma-separated cross-origin hosts; blank = CORS not installed. |
| `HTTP_EXPOSE_OPENAPI` | *(blank)* | Serve Swagger UI + the spec at `/openapi`; blank follows the mode. |
| `CHECKER_URL` | *(blank)* | The lint/semantic checker sidecar; blank = off (every check carries `CHECKER_UNAVAILABLE`). |
| `CHECKER_TOKEN` | *(blank)* | Shared secret sent as `X-Checker-Token`. |
| `CHECKER_TIMEOUT_MS` | `20000` | Per-call budget for the sidecar. |
| `CONTRACT_MAX_DOCUMENT_BYTES` | `2097152` | Ceiling for one contract document (413 above it). |
| `WEB_STATIC_DIR` | *(blank)* | Directory of the built SPA to serve; blank in local dev (Vite serves it). The image sets `/app/web`. |
| `MAIL_TRANSPORT` | `log` | `log` (dev only — production refuses it), `smtp`, or `disabled` (email features answer 503; the image default). |
| `SMTP_HOST` | *(blank)* | SMTP server (required for `smtp`). |
| `SMTP_PORT` | `587` | SMTP port. |
| `SMTP_USER` | *(blank)* | SMTP user (optional). |
| `SMTP_PASSWORD` | *(blank)* | SMTP password (optional). |
| `SMTP_STARTTLS` | `true` | STARTTLS on the SMTP connection. |
| `MAIL_FROM` | `covenant@localhost` | Sender address of every outbound email. |
| `MAIL_APP_URL` | *(blank)* | Absolute URL of this deployment — emails carry a sign-in link when set. |
| `POSTGRES_JDBC_URL` | `jdbc:postgresql://localhost:5434/covenant` | Flyway's JDBC URL. |
| `POSTGRES_R2DBC_URL` | `r2dbc:postgresql://localhost:5434/covenant` | The runtime R2DBC URL. |
| `POSTGRES_USER` | `covenant` | Database user. |
| `POSTGRES_PASSWORD` | `covenant` | Database password. |

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

## Contract usage from Toadie

Covenant can read the existing Port ontology from Toadie: link a contract to one or more API or dataset
entities and see provider/consumer services, systems and teams. Administrators configure the
connection under **Toadie connections**; contract writers manage links and request refresh.
Toadie must be **2.12.0 or newer**, with GraphQL integration enabled and a dedicated integration client key. Its
local Compose demo enables it already; no Toadie code change is required.

For local Compose, use `http://host.docker.internal:8081` as the server URL and
`http://localhost:8081` as the browser URL. Keep the key in the encrypted connection setting,
never in Git. Production connections require HTTPS. Failed refreshes retain the last known
usage and show it as stale; architecture usage does not establish an exact version or release
line. Refresh compares ontology revisions across every page and retries once if the graph changes.
Scheduled refresh skips the full scan when the revision is unchanged; manual refresh always
reads the complete mapped graph. The last-successful-refresh time includes successful
same-revision freshness checks. See [the integration reference](.claude/docs/toadie-integration.md)
for compatibility and operational details.

For ODCS, create a second connection to the same instance and choose **Advanced mapping →
Use dataset mapping**. It reads Toadie 2.13's `dataset` blueprint through service relations
`produces_datasets` and `consumes_datasets`; those ontology definitions must be present in
Toadie. Keep the API connection for OpenAPI/AsyncAPI. Link one or more datasets explicitly
from the ODCS contract's **Edit Toadie links** dialog. Database dependencies do not imply
dataset consumption. Optional adoption entities can be read using a separate opt-in mapping;
see [declared adoption](.claude/docs/toadie-adoption.md). See the
[ODCS setup instructions](.claude/docs/toadie-integration.md#configure-odcs-dataset-usage).

## Registry metadata from Toadie

Since **1.0.0**, administrators can enable registry synchronization on a Toadie connection,
preview selected Port domains, systems and teams, and import them or explicitly link them to
existing Covenant records. Link domains before systems; a system without a source domain
needs an explicit local fallback. Nested source domains are flattened only after acknowledgement.

Linked names and configured descriptions follow Toadie during refresh. Source information,
freshness and conflicts stay visible in the registries. Missing upstream entities preserve
local records, contracts and Environments. Team memberships and contract permissions remain
managed in Covenant. Detach a source to return its metadata to local management; local-only
registries continue to work. No Toadie code changes are required. See
[registry synchronization](.claude/docs/toadie-registry-sync.md) for mapping and failure behavior.

## Planned deprecation and retirement

Release-line policies can announce deprecation and support-end dates, link to a replacement
contract or major line, and explain migration steps. Followers receive in-app deadline reminders
at the current 30-day, 7-day, or reached-deadline window (UTC; checked hourly). Existing support-end
dates become eligible on upgrade. Dates never change version lifecycle automatically.

Before retiring a version or ending line support, review the declared consumers and teams from
Toadie, the observation's freshness, and the migration plan. Usage remains contract-level;
Covenant does not assume which release line those services use. This feature needs no Toadie
changes. See [release-line rules](.claude/docs/release-lines.md) for details.

Use **Download report** in a release line's impact dialog to share its saved migration plan
as a Markdown file. The report includes support and deprecation dates, replacement and
recommendation, full migration instructions, and declared provider/consumer services with
systems and teams. It reads the complete local Toadie cache, independently of table filters
and paging, without refreshing Toadie. Generation, plan-update and last-successful-observation
timestamps are distinct. Missing mappings and stale/unavailable observations remain visible;
runtime version or major-line adoption is unknown; source declarations are shown separately,
and an empty observation does not establish
that retirement is safe. Every authenticated reader can download a report. Reports exceeding
the 8 MiB export budget show an error instead of downloading incomplete data.

The **Lifecycle** page (`/lifecycle`) brings major release lines together across the catalog.
Filter by owner, domain, system, contract type, support status or deadline window, then use
attention counts to find approaching deadlines, reached support-end dates, incomplete migration
plans and uncertain usage. Open the existing impact review or edit a policy with the usual owner
permissions. Consumer counts describe the whole contract; stale or missing data is explicit.
Dates remain advisory, and this overview needs no changes to Toadie.

## Version review and discussion

Open **Reviews** on a contract version to request feedback before publication. A contract
writer can request review while the version is PROPOSED. Other signed-in collaborators can
approve or request changes, and everyone can discuss the open review. The requester can
comment but cannot decide on their own request.

Decisions refer to a specific content revision. Editing or synchronizing changed text makes
the open review outdated, preserves its discussion, and allows a fresh request. Restoring old
text does not restore its approvals. Leaving PROPOSED closes the review; publication remains
an explicit owner action and is never blocked by missing reviews or requested changes.

Reviews are optional, with no assigned reviewers or approval quota. See
[the review reference](.claude/docs/version-reviews.md) for permissions and history semantics.

The **Review inbox** brings these requests together. It defaults to contracts you own, your
teams own, or you follow; switch to Owned, Followed, or All contracts as needed. Filter for
reviews awaiting your decision, changes requested, or proposals needing a fresh review after
an earlier round closed. Search and page results, then open the current document's Reviews
section. Only each proposed version's latest round appears; earlier discussion stays in its
history. See [the inbox reference](.claude/docs/review-inbox.md).

## Product backlog

See [the backlog](BACKLOG.md) for proposed next steps and deferred work, including CI/CD integration.

## License

MIT — see [LICENSE](LICENSE).
