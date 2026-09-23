# Product backlog

Updated 2026-09-24. This file tracks outstanding work only; implemented behavior and
release history belong in [README.md](README.md), the
[application changelog](web/src/changelog/entries.ts), and the topic guides under
`.claude/docs/`. Entries are proposals, not delivery commitments.

## Contract-check follow-ups

These findings from the 2026-09-23 contract review still need a decision or implementation:

- **Unsupported AsyncAPI/ODCS versions:** unsupported spec versions currently produce
  waivable findings rather than a hard rejection. Decide and document the supported-version
  gate; see `server/src/main/kotlin/contracts/checks/DocumentParser.kt`.
- **Finding limit:** the stated 500-finding cap can return or store 501 entries after adding
  the truncation marker. Align response, storage and documentation; see
  `checker/src/findings.ts` and `server/src/main/kotlin/contracts/checks/Finding.kt`.

## Verification gap

The 2026-09-23 check-up's web `npm audit` was not completed: automatic approval review
rejected sending workspace dependency names and versions to the advisory service. Revisit
that network check when authorized. The checker and E2E audits reported zero vulnerabilities
at that time; no comprehensive JVM/container vulnerability scan was performed.

## Deferred product and engineering work

- **Team CI/CD integration.** Parked at the user's request until teams are ready. A future
  design can check a candidate against a selected contract/major line, return machine-readable
  validation and compatibility results, and provide a small CLI/workflow example with scoped
  automation credentials. Publication remains explicit.
- **Registry page refactoring.** Domains, Systems, Environments and Teams duplicate list/form
  behavior. Reassess the shared abstraction with the registry-source and conflict states added
  in v1.0.0, then refactor only the common behavior that remains stable across those pages.

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
