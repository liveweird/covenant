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

Refresh each changed workspace from its committed lockfile. Run its documented lint, dead-code,
type, test/coverage and build gates; regenerate frontend API types and confirm there is no
unexplained schema drift. Run `npm audit` in all three workspaces and distinguish runtime
advisories from development-only exposure. For Gradle changes, run the complete build and
alignment gate on the pinned JDK; database changes need a fresh Testcontainers database and the
complete server suite, including concurrency cases. An Exposed stall investigation needs
`:server:test --rerun-tasks`, not a passing isolated CRUD test.

Build the application and checker images, verify health and representative sample contracts,
and run the full Playwright journeys after cross-stack updates. Preserve the user's sample
records and clean up only verification-owned data. Existing Dependabot PR checks are evidence
for their recorded commit only; refresh against current main before merging. A version check
or npm audit is not a comprehensive JVM/container vulnerability scan.
