# Dependency maintenance

Dependency declarations and lockfiles are the source of truth. Keep Gradle and the three npm
workspaces independent; do not upgrade to the newest major merely because it is available.

Outstanding coordinated major-version migrations are tracked in the
[product backlog](../../BACKLOG.md). Recheck current upstream compatibility and advisory data
before acting on a dated triage decision.

## Update automation

`.github/dependabot.yml` checks Gradle, each npm workspace, GitHub Actions, Dockerfiles,
Kubernetes image references, and Compose weekly. Ordinary minor/patch updates are grouped by
workspace. Vitest and its coverage plugins form a separate group so their exact-version peers
move together. The Gradle bulk group excludes Exposed and the Netty/Reactor/OpenTelemetry
families: their pins need individual compatibility review before integration. Exclusion from a
group does not ignore an update or exempt it from review.

Dependabot vulnerability alerts and security update PRs are enabled in the GitHub repository
settings. These are separate from the version-update configuration. Security updates still need
the normal checks; never auto-merge a breaking migration to clear an alert.

Dependabot does not cover every declaration here. At each monthly maintenance pass, inspect the
exact JDK pin in `mise.toml`, Ktor's imported catalog in `settings.gradle.kts`, image literals in
`server/src/test/kotlin/{PostgresTestSupport,KafkaTestSupport}.kt`, and the runtime versions actually
used by cached images and local tools. Compare official release metadata, not just open PRs.

## Compatibility boundaries

- Keep TypeScript on the supported shared major across web, checker and E2E. TypeScript 7 needs
  a coordinated migration: the current ESLint tooling requires TypeScript below 6.1, and
  `openapi-typescript` uses the compiler API and declares TypeScript 5. The existing web
  `--legacy-peer-deps` exception permits TypeScript 6; it is not permission to bypass arbitrary
  peer conflicts. In particular, all Mantine packages must use the same release.
- Match `@types/node` to the Node runtime major. Keep production on an LTS line; newer types can
  expose APIs missing at runtime even if the build passes.
- Upgrade React/React DOM and their type packages together. Upgrade Vitest and
  `@vitest/coverage-v8` together and validate coverage without lowering floors.
- Preserve the Jackson 2/networknt 2 boundary until the full validator stack can migrate:
  networknt 3 accepts Jackson 3 `tools.jackson` nodes, which are incompatible with the Jackson 2
  nodes shared by the current parser and schema/Avro pipeline.
- Read the current rationale in `gradle/libs.versions.toml` before moving Exposed,
  Netty/Reactor Netty, or OpenTelemetry. Inspect published BOMs/POMs as well as release notes.
  `:server:checkDependencyAlignment` must pass, but alignment alone does not prove compatibility.
- The checker's AsyncAPI and Spectral packages share a mixed Stoplight type graph. Review their
  peer/transitive requirements and real fixture verdicts before changing the direct type major.

## Runtime and image verification

Keep PostgreSQL on the same tested major/minor across Compose, Kubernetes and Testcontainers.
External images use exact release tags and reviewed multi-platform digests where available;
refresh both together. The tag describes the intended version, while the digest selects bytes.
A local rebuild without an updated base digest does not pick up security fixes. Verify
`java -version`, `node --version`, and `postgres --version` inside the selected images when
changing runtime pins. A `mise` version identifier may differ from the Java version it installs;
record the actual binary version when verifying a JDK patch.

Use the build-specific local Kubernetes image workflow in
`.claude/skills/run-stack/SKILL.md`. Do not redeploy a mutable `latest` tag and assume every node
has the same image. Never remove development database volumes as part of an update.

## Acceptance checks

Refresh each changed workspace from its committed lockfile. For Gradle changes, regenerate the
project and buildscript locks with the command below and review their diff. Run each changed
workspace's documented lint, dead-code, type, test/coverage and build gates; regenerate frontend
API types and confirm there is no unexplained schema drift. Run `npm audit` in all three
workspaces and distinguish runtime
advisories from development-only exposure. For Gradle changes, run the complete build and
alignment gate on the pinned JDK; database changes need a fresh Testcontainers database and the
complete server suite, including concurrency cases. An Exposed stall investigation needs
`:server:test --rerun-tasks`, not a passing isolated CRUD test.

Build the application and checker images, verify health and representative sample contracts,
and run the full Playwright journeys after cross-stack updates. Preserve the user's sample
records and clean up only verification-owned data. Existing Dependabot PR checks are evidence
for their recorded commit only; refresh against current master before merging. A version check
or npm audit is not a comprehensive JVM/container vulnerability scan.

## Vulnerability scan record (2026-09-24)

With Node 24.21.0 and npm 11.19.0, `npm audit --json --audit-level=low` reported zero
vulnerabilities in web, checker and E2E. Trivy 0.74.0, using freshly downloaded vulnerability
and Java databases, scanned `docker save` archives of the four images selected by the running
Compose stack: `covenant-app`, `covenant-checker`, `postgres:18.6-alpine` and
`axllent/mailpit:v1.31.2`. The scanner ran against the archives without access to the Docker
socket. JSON reports are in `/private/tmp/covenant-vuln-20260924/` on the verification host;
that temporary path is not a durable artifact.

| Image | OS packages | Language packages |
| --- | --- | --- |
| App | 44 MEDIUM, 4 LOW (15 MEDIUM with listed fixes) | 2 HIGH in SCRAM, 2 MEDIUM in lz4-java and commons-configuration2 (all with listed fixes) |
| Checker | 0 | 4 HIGH, 5 MEDIUM, all in the base image's global npm CLI (all with listed fixes) |
| Postgres | 0 | `gosu` Go standard library: 1 CRITICAL, 21 HIGH, 21 MEDIUM, 2 LOW, 1 UNKNOWN (all with listed fixes) |
| Mailpit | 0 | 1 UNKNOWN in bundled x/crypto/openpgp (no listed fix) |

The app image contained 195 detected Java packages, including the deployed runtime jars.
`scram-client` 3.2 is transitive through `r2dbc-postgresql` 1.1.2.RELEASE. The checker findings
are in `/usr/local/lib/node_modules/npm/`, not `/checker/node_modules/`; `npm audit` of the
workspace therefore does not include them. The Postgres findings identify packages in its
startup helper, not demonstrated reachable vulnerabilities in the database service. This
baseline scan did not inspect Gradle build/test-only dependencies, image configuration, secrets
or runtime exploitability.

## Security follow-up (2026-09-24)

The server now constrains the vulnerable runtime transitives to SCRAM 3.3, lz4-java 1.11.1 and
commons-configuration2 2.15.0. Both Temurin base digests were refreshed. On arm64, the refreshed
JRE still contained 15 fixable MEDIUM findings in libexpat1, libsqlite3-0 and perl-base, so the
runtime stage upgrades them from signed Noble repositories and checks minimum fixed versions.
This avoids exact revision pins disappearing from live package indexes. Its final Trivy scan
reported **zero Java findings** and **29 MEDIUM / 4 LOW OS findings, none with a listed fix**. The checker runtime no
longer includes the unused global npm CLI and its bundled packages; its final image scan
reported **zero findings**. The three npm workspace audits remained clear.

Gradle strict dependency locking now records resolved modules in seven root/settings/core/server
project and buildscript lockfiles, including test classpaths and the Ktor/Kotlin/Detekt/Kover
plugin classpaths. The Foojay settings plugin resolves before buildscript locking; a shared
`foojayResolverVersion` property drives both its application and a locked root audit
configuration. Regenerate with
`./gradlew :verifySettingsPluginAudit :buildEnvironment :core:buildEnvironment :server:buildEnvironment :dependencies :core:dependencies :server:dependencies --write-locks`,
then review the diff; do not hand-edit generated locks. The server plugin graph initially exposed
HIGH Jackson and Plexus and MEDIUM Log4j findings. Script-classpath constraints now select
fixed versions; Trivy 0.74.0 scanned all seven locks with
`fs --scanners vuln --include-dev-deps` and reported zero advisories across 3 root, 1 settings,
34 root-plugin, 104 core, 2 core-plugin, 392 server and 61 server-plugin detected packages. CI
resolves the settings-plugin audit configuration, repeats the lockfile scan, uploads the full
JSON report and fails for HIGH or CRITICAL findings. The Docker build copies the locks before
resolving server dependencies. Lockfile scanning reports known package advisories; it does not
prove reachability or inventory Gradle's own distribution internals.

The Postgres `gosu` findings remain visible in a package-presence scan. Upstream's
[gosu security guidance](https://github.com/tianon/gosu/security) calls for vulnerable
function checks; the pinned image invokes `gosu` only during startup to drop privileges,
and binary symbol inspection found no affected TLS, HTTP, archive or Windows call paths.
The current upstream Postgres image still ships the same `gosu` build, so changing its digest
would not clear the alerts. Mailpit's `GO-2026-5932` concerns
[`x/crypto/openpgp`](https://pkg.go.dev/vuln/GO-2026-5932); binary inspection found no OpenPGP
symbols in the pinned, current Mailpit release. These are **reachability assessments, not proof
that every reported issue is harmless**. Keep the image pins and revisit the assessments when
upstream images or advisory details change.

Verification on the rebuilt local stack: the full Gradle build passed (527 server tests,
detekt, coverage and alignment gates), both app and checker images built, `/api/v1/ready`
returned OK, all seven sample documents passed check-only validation, and all 87 Playwright
journeys passed. Compose retained the existing Postgres and Mailpit containers and data volume.

## Buildscript advisory follow-up (2026-09-26)

The next CI scan reported CVE-2026-84939 in the root plugin classpath's transitive
`org.freemarker:freemarker` 2.3.32. A root buildscript constraint selects Apache FreeMarker
2.3.35, and the regenerated root buildscript lockfile records that version. This is a
build-time dependency; it is not packaged in the application runtime.
