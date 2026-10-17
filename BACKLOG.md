# Product backlog

Updated 2026-10-17. This file tracks outstanding work only; implemented behavior and
release history belong in [README.md](README.md), the
[application changelog](web/src/changelog/entries.ts), and the topic guides under
`.claude/docs/`. Entries are proposals, not delivery commitments.

## Security monitoring

- **Upstream image advisories.** The 2026-09-24 final app scan has 29 MEDIUM and four LOW
  Ubuntu findings without listed fixes. Postgres `gosu` and Mailpit retain package-presence
  findings with no affected call path identified in the current binaries. Reassess these
  findings when upstream images or advisory details change; the scan and triage record is in
  [dependency maintenance](.claude/docs/dependencies.md).

## Deferred product and engineering work

- **Team CI/CD integration.** Parked at the user's request until teams are ready. A future
  design can check a candidate against a selected contract/major line, return machine-readable
  validation and compatibility results, and provide a small CLI/workflow example with scoped
  automation credentials. Publication remains explicit.

- [new] **Gradle verification metadata pruning.** `--write-verification-metadata` only adds
  entries, never removes them, so `gradle/verification-metadata.xml` grew from 624 components
  when strict verification landed on 2026-10-14 to 725 across four bump rounds on 2026-10-17.
  153 of its 491 distinct modules now carry more than one version, 234 entries beyond the
  first. Roughly 108 of those extras are POM-only parent/BOM metadata that the resolution graph
  legitimately references at several versions (`commons-parent` at twelve, `junit-bom` at
  twelve), so deduplication is not a valid prune; the remaining ~126 carry a real JAR and are
  largely dead (`kotlin-stdlib` at five versions, only two of them live). Stale checksums are
  inert, since verification consults only the entries a build actually resolves, so this is
  housekeeping rather than a defect. Decide whether to prune periodically by regenerating from
  an empty Gradle home and reviewing the resulting diff for provenance — which
  [dependency maintenance](.claude/docs/dependencies.md) requires be done deliberately, not by
  rerunning the writer blindly.

- [new] **Tier-D registry abstraction.** The 0.5.1 checkup deferred collapsing the shape the
  curated registries share. `DomainService` (128 lines), `SystemService` (157), `TeamService`
  (246) and `EnvironmentService` (198) are four independent implementations of the same five
  methods — `list(filter, paging)`, `read`, `create`, `update`, `delete` — each repeating soft
  delete and count-plus-rows paging, with name-conflict handling in three of the four; their
  route files repeat the shape across another 426 lines, and there is no shared base. Weigh
  against the deliberate convention before acting: CLAUDE.md's feature template tells a new
  registry to copy `teams/`, so this duplication is currently the documented idiom and an
  abstraction would replace it. Teams' membership sub-collection and Environments'
  `resolveTarget` fall outside the common shape and would stay bespoke either way.

## Dependency migrations to revisit

The 2026-09-22 dependency triage deferred these coordinated major updates. Recheck upstream
compatibility, current PR state and advisory data before scheduling them; the earlier snapshot
is not a current dependency or security assessment.

- **Node runtime and types:** Runtime PRs
  [#26](https://github.com/liveweird/covenant/pull/26) and
  [#28](https://github.com/liveweird/covenant/pull/28), and types PRs
  [#9](https://github.com/liveweird/covenant/pull/9),
  [#14](https://github.com/liveweird/covenant/pull/14) and
  [#16](https://github.com/liveweird/covenant/pull/16), need one tested runtime/type-major
  migration on an LTS line.
- **JDK:** [PR #27](https://github.com/liveweird/covenant/pull/27) needs a supported,
  coordinated toolchain/build/runtime migration.
- **TypeScript 7:** PRs [#4](https://github.com/liveweird/covenant/pull/4),
  [#6](https://github.com/liveweird/covenant/pull/6) and
  [#7](https://github.com/liveweird/covenant/pull/7) need compatible ESLint and
  API-generation tooling, then a three-workspace migration.
- **networknt 3:** [PR #10](https://github.com/liveweird/covenant/pull/10) crosses the
  current Jackson 2 validator boundary and needs a coordinated parser/schema migration.
- **Stoplight types 14:** [PR #3](https://github.com/liveweird/covenant/pull/3) must be
  evaluated with the AsyncAPI parser and Spectral graph; changing only the direct pin does
  not align their transitive requirements. Check whether the unused direct pin is needed.

The ongoing compatibility rules and maintenance checks are in
[dependency maintenance](.claude/docs/dependencies.md).
