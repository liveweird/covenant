# Product backlog

Updated 2026-09-21. These are proposals, not delivery commitments. Implemented behavior is
documented in [README.md](README.md) and the release changelog.

## Next session: remaining quality-checkup fixes

Session integrity shipped in **0.14.1**. Version **0.14.2** addresses the first three
validation findings: OpenAPI comparison, checker request sizing and Save anyway baselines.
The audit examined `dfd4677`; the session fixes are merged and pushed in [PR #36](https://github.com/liveweird/covenant/pull/36),
main commit `60936e1`, and deployed through Docker Compose at `http://localhost:8082`.
At release verification, exact-main CI passed and all four services were healthy. The release
passed 441 backend tests and 595 frontend tests, coverage/static gates, independent review and browser verification.

Completed: stale refresh/login/MFA responses cannot replace or clear a newer session; logout
clears local state immediately; V20 credential revisions invalidate earlier refresh tokens
and pending MFA challenges on password change/reset/bootstrap rotation. Deterministic tests
cover same-instant password changes and deferred responses. Legacy refresh tokens require a
fresh sign-in. Kubernetes uses `Recreate` to prevent mixed old/new password writers; no
Kubernetes deployment was performed.

Six findings remain, in the recommended implementation order below. Reproduce each issue in
permanent regression tests, then implement and independently review the fix.

| Priority | Remaining issue and source | Acceptance check / evidence |
| --- | --- | --- |
| P2 | **Parent soft deletion races child creation/moves.** [SystemService.kt](server/src/main/kotlin/systems/SystemService.kt), [DomainService.kt](server/src/main/kotlin/domains/DomainService.kt), [ContractService.kt](server/src/main/kotlin/contracts/ContractService.kt), and [TeamService.kt](server/src/main/kotlin/teams/TeamService.kt) use unlocked active-parent checks and child counts. | Share a parent-lock protocol across attach/move/delete. Barrier-controlled service tests must prevent active systems/contracts referencing deleted domains/systems/owner teams. Two PostgreSQL scratch-table interleavings confirmed the FK/soft-delete flaw; full service concurrency regressions are still needed. |
| P2 | **SQL prerelease sorting is lexicographic.** [ContractVersionService.kt](server/src/main/kotlin/contracts/ContractVersionService.kt) and [ContractService.kt](server/src/main/kotlin/contracts/ContractService.kt) sort the raw prerelease string. | Lists, major-filtered lists, errors sorted by version and exports must put `1.2.0-rc.10` above `1.2.0-rc.2` descending, including across page boundaries. SQL output was compared with the actual Kotlin SemVer comparator. |
| P2 | **Toadie connection switch retains selectable rows from the old connection.** [ToadieLinksModal.tsx](web/src/components/ToadieLinksModal.tsx) uses `keepPreviousData` across connection identities. | Defer connection B's response, switch from A, and prove an old A row cannot be submitted under B. Include colliding instance-local entity IDs. Actual-component reproduction submitted the stale ID under the new connection. |
| P2 | **Published-version sync loses its source major line.** [SyncVersionModal.tsx](web/src/components/SyncVersionModal.tsx) seeds document state and `?from=`, but [NewVersion.tsx](web/src/pages/NewVersion.tsx) ignores `from` whenever state exists. | Render the real destination: syncing a published 1.x source while 2.x exists must retain the 1.x starting context and fetched content. Static cross-component evidence; the existing test substitutes a dummy destination. |
| P2 | **Some pickers stop after page one.** System selectors in create/edit/import/environment forms, [OwnerSelect.tsx](web/src/components/OwnerSelect.tsx), [ContractFilterControls.tsx](web/src/components/ContractFilterControls.tsx), and [VersionDiff.tsx](web/src/pages/VersionDiff.tsx) fetch only the first 100 rows. | Record 101 must be selectable through paging or server-side search. Reuse existing all-page helpers where appropriate. Direct diff links can still load IDs outside the picker; do not misstate that as inaccessible data. Static evidence; add large-catalog fixtures. |
| P2 | **Environment link-local literal validation is incomplete.** [TargetValidation.kt](server/src/main/kotlin/environments/TargetValidation.kt) only checks dotted `169.254.*` and `fe80:` prefixes. | Reject equivalent numeric IP literals and the full IPv6 link-local range, while preserving intentionally permitted internal targets. Requires an ADMIN-created Environment; this is not an unauthenticated SSRF finding. Local address-resolution probe confirmed the representations; no live metadata requests were made. |

Follow-ups outside the six fixes:

- Correct the stale `CLAUDE.md` package summary saying `SemVer > highest` when touching version
  ordering; major-line backports are supported.
- Consider explicit hard capacities for login/reset/MFA maps. Their current pruning removes
  expired entries but does not bound fresh entries. This is a hardening/test-gap note, not a
  demonstrated resource-exhaustion incident.
- Do not reintroduce rejected findings: Kafka password retention follows the documented PUT
  contract; negative path segments are intentionally rejected globally. Chunked checker error
  delivery was unconfirmed and is not counted.

Preserve the seven sample contracts and the configured Toadie connection/cache. The user's
untracked `covenant-handoff-reply.md` is intentionally excluded from commits; do not overwrite
or delete it. Toadie's newly shipped capabilities are separate backlog work, not regressions.

## Proposed next steps

1. **Shareable migration and impact reports.** Export a release line's dates, replacement,
   migration guidance and declared affected services/teams for planning discussions. Include
   observation timestamps, missing/stale usage warnings, and the limit that exact version
   adoption is unknown. Reuse the existing Toadie integration; no Toadie changes are expected.

## Deferred

Related cross-project proposals are collected in the
[Toadie implementation handoff](.claude/docs/toadie-handoff.md). They are optional improvements
to the existing integration and have their own implementation scope.

- **Connecting Covenant to teams' CI/CD pipelines.** Parked at the user's request because the
  team is not ready to adopt it. Revisit when the team wants pipeline checks. The proposal
  includes checking a candidate against a selected contract/major line, machine-readable
  validation and compatibility results, a small CLI/workflow example, and scoped automation
  credentials. Publication would remain explicit. This deferral concerns the product
  integration; Covenant's own build, test and deployment workflows remain in use.

## Recently completed

- Validation fixes (0.14.2): OpenAPI
  breaking detection is independent of JSON formatting and preserves YAML anchors; the checker
  accepts two maximum-size documents with JSON overhead; create/edit Save anyway checks retain
  the contract baseline. Regression tests cover these paths, including hard-error rejection.
  Verified with 442 backend, 597 frontend and 51 checker tests, build/static/coverage gates,
  independent review, real maximum-size checker requests and create/edit browser flows.

- Session integrity, credential-revision revocation and deterministic regression tests (0.14.1).

- Review inbox with personal/followed/catalog scopes and latest-round attention (0.14.0).

- Optional version review and discussion, content-bound decisions and preserved review history (0.13.0).

- Catalog-wide lifecycle overview, deadline/plan attention and cached contract-level usage (0.12.0).

- Parallel supported major release lines and backports (0.9.0).
- Declared contract usage from Toadie's Port ontology (0.10.0).
- Deprecation/support-end planning, replacement and migration guidance, follower reminders,
  and consumer-impact review before retirement or end of support (0.11.0).
