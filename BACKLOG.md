# Product backlog

Updated 2026-09-21. These are proposals, not delivery commitments. Implemented behavior is
documented in [README.md](README.md) and the release changelog.

## Quality-checkup fixes

Session integrity shipped in **0.14.1**. Version **0.14.2** addresses the first three
validation findings: OpenAPI comparison, checker request sizing and Save anyway baselines.
The audit examined `dfd4677`. The preceding release, **0.14.2**, was merged through
[PR #37](https://github.com/liveweird/covenant/pull/37) and
[PR #38](https://github.com/liveweird/covenant/pull/38), main commit `4fa5f1c`, and deployed through
Docker Compose at `http://localhost:8082`. Its exact-main CI and deployed checks passed.

Completed: stale refresh/login/MFA responses cannot replace or clear a newer session; logout
clears local state immediately; V20 credential revisions invalidate earlier refresh tokens
and pending MFA challenges on password change/reset/bootstrap rotation. Deterministic tests
cover same-instant password changes and deferred responses. Legacy refresh tokens require a
fresh sign-in. Kubernetes uses `Recreate` to prevent mixed old/new password writers; no
Kubernetes deployment was performed.

The six remaining audit findings shipped in **0.14.3** through
[PR #39](https://github.com/liveweird/covenant/pull/39), main commit `a60c137`, with Docker Compose
deployment and exact-main CI verified: 451 backend
tests, 606 frontend tests, build/static/coverage gates, independent review, API conformance and
browser checks passed. The catalog was also checked read-only: no active systems, contracts or environments reference deleted
parents in the affected relationships.

| Fix | Regression coverage |
| --- | --- |
| Parent soft deletion races child creation/moves | Shared parent-lock protocol across attach/move/delete; deterministic service interleavings and import rollback. |
| SQL prerelease sorting | Identifier-aware SQL SemVer order before paging; version lists, major filters, Errors and exports match the Kotlin comparator. |
| Toadie connection switch retains stale selectable rows | Deferred connection response with colliding instance-local IDs; old rows cannot be selected for the new connection. |
| Published-version sync loses its source major line | The real new-version destination retains source major and fetched content while a newer major exists. |
| Some pickers stop after page one | System create/edit/import/environment forms, owner-team/filter choices and version comparisons include record 101. |
| Environment link-local literal validation | Single-decimal IPv4, full IPv6 link-local range, scoped and mapped addresses rejected; internal targets stay permitted without DNS. |

Additional hardening notes:

- Consider explicit hard capacities for login/reset/MFA maps. Their current pruning removes
  expired entries but does not bound fresh entries. This is a hardening/test-gap note, not a
  demonstrated resource-exhaustion incident.
- Do not reintroduce rejected findings: Kafka password retention follows the documented PUT
  contract; negative path segments are intentionally rejected globally. Chunked checker error
  delivery was unconfirmed and is not counted.

Preserve the seven sample contracts and the configured Toadie connection/cache. The user's
untracked `covenant-handoff-reply.md` is intentionally excluded from commits; do not overwrite
or delete it. Toadie's newly shipped capabilities are separate backlog work, not regressions.

## Migration and impact reports

**Shareable migration and impact reports (0.15.0).** Download a saved release line's support
and deprecation dates, replacement, recommendation, migration guidance and complete cached
provider/consumer services with systems and teams as localized Markdown. The report carries
separate generation/plan/observation timestamps, missing/stale usage warnings and explicitly
unknown exact version/line adoption. It reuses Toadie's current integration without upstream
changes or refreshes. A read-only local database snapshot keeps the plan and observation
consistent; an 8 MiB export budget rejects oversized reports instead of truncating them.
Implementation and verification are complete: 453 backend tests, 614 frontend tests, seven
real-browser lifecycle/report journeys, static/build/coverage gates, API conformance and
independent review pass. Version and bilingual changelog are updated.

## Revision-aware Toadie refreshes

Implemented in **0.16.0**: requires Toadie 2.12+, compares revisions across every page
and at scan completion, restarts once within shared budgets, and preserves the last complete
cache on failure. Scheduled checks reconfirm unchanged revisions; manual refresh always scans,
promoting an in-flight revision check when needed. V22 stores the verified remote revision.
Verification passed: 460 backend tests with build/static/coverage gates, frontend build/static
and 50 changelog/locale tests, eight browser journeys, an unchanged-revision integration probe,
API conformance and independent review. Version and bilingual changelog are updated.
No changes are required in Toadie.

Remaining Toadie proposals: verify/document separate dataset mappings for ODCS, then design a
reader for optional declared adoption entities. Toadie's narrower key scopes and incremental
change feed were declined upstream; they are not pending Covenant prerequisites. Adoption
blueprints are importable ontology definitions, so absence must remain valid.

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
