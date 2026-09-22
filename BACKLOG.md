# Product backlog

Updated 2026-09-22. These are proposals, not delivery commitments. Implemented behavior is
documented in [README.md](README.md) and the release changelog.

## Open defects and operational gaps

**Investigate intermittent Environments delete display.** During ODCS verification on
2026-09-22, `e2e/tests/environments.spec.ts` timed out at the post-delete row-removal
assertion (line 87): the row remained visible although the DELETE succeeded and the active
API inventory no longer contained the environment. The full run passed 72/73 tests, including
all Toadie journeys; the same Environments flow passed in a targeted six-test rerun on the
final Compose image. The trace shows the filtered GET overlapped DELETE and returned the old row; no GET followed
the successful deletion. The exact invalidation timing remains unconfirmed. Start by waiting
for the debounced filtered GET to settle before edit/delete, then check whether the race
reproduces. Trace/screenshot evidence was retained, and test-owned leftovers were removed
through the normal APIs. No Environments behavior or assertions were changed in the ODCS work.


**Nightly logout regression fixed in 1.0.2.** The failed
2026-09-21 and 2026-09-22 runs were investigated using their Playwright attachments. The
regular reader signed in successfully and saw the previous contract with read-only controls;
the failure was a stale return destination, not a rejected login. Explicit logout's synchronous
auth notification rerendered `RequireAuth` on the old protected route before navigation to
`/login` committed, saving that route for the next login. Removing the redundant notification
lets explicit navigation read the cleared session without capturing the old destination.
Immediate local logout, best-effort revocation and guarded deep-link return behavior remain intact.

The original failure was reproduced against the deployed 1.0.1 bundle. A regression using
real route guards failed before the fix and passed after it; the browser regression signs out
from Contracts, delays the real logout response and signs in without reloading or clearing
browser storage. The original reader assertion remains unchanged. All **72 browser tests passed without retries**
against the corrected production frontend build and the existing Compose backend, including
the older inference case. All **638 frontend tests**, coverage, build/static gates, browser-test
static/scenario/setup gates passed. Test-created records were removed and the original catalog
inventory was preserved. Independent review found no actionable issues.

**Sign-out notice follow-up (1.0.3).** The main CI run after 1.0.2 exposed an intermittent
missing confirmation banner: the login page consumed a one-time flag during render, so a
suspended and abandoned render could discard it. Rendering now reads the flag without
mutation; the committed page acknowledges it in an effect. A deterministic suspended-render
regression covers this path and a fresh mount verifies the notice is shown only once.

**Browser-test synchronization follow-up (2026-09-22).** Three timing
failures seen in the release browser runs are corrected: contract teardown waits for the
destination heading before opening its actions; inference waits for the version-creation
response and asserts `201` before checking navigation; user creation waits for the password's
revealed state before reading it. Scenarios and the browser coverage map are synchronized.
All **72 browser tests passed without retries**, and a separate inference run passed with the
real save response held for **11 seconds**, beyond the previous heading assertion's timeout.
Global timeouts are unchanged. Browser-test lint, typecheck, dead-code, scenario and four setup
checks passed; independent review found no actionable issues. These are test-only changes;
the app version remains **1.0.3**.

**Release/tag consistency restored (2026-09-22).** The sixteen missing versions from 0.8.1
through 1.0.3 now have annotated tags at their original main release commits and bilingual
GitHub releases copied from the corresponding changelog snapshots. The four existing
tags/releases remain intact; v1.0.3 was marked Latest at the backfill. Backfilled notes distinguish the original
changelog date from the actual publication date. The
[application release process](.claude/docs/app-releases.md) requires tag/release verification
for future publication; later documentation/test-only commits never move existing tags.

## Dependency updates

The eleven open Dependabot PRs were triaged on **2026-09-22**. All are explicitly deferred:
Node 26 and its type packages await an LTS runtime migration; JDK 24 does not match the JDK/JRE
21 toolchain; TypeScript 7 exceeds current tooling peer ranges; networknt 3 crosses the Jackson
2 boundary; Stoplight types 14 cannot align the current parser/Spectral graph by itself.
They remain open; no security alerts were dismissed or update rules suppressed.

Compatible npm updates are implemented for **1.0.4**: aligned Mantine
9.6.2, CodeMirror state/view fixes, TanStack Query 5.103.2, react-i18next 17.0.15, Tabler icons
3.48.0, and typescript-eslint 8.70.1 across all three workspaces. Node/JDK image digests still
match their committed pins. GitHub reported no open Dependabot security alerts, and all three
npm audits reported zero known vulnerabilities.

Verification passed: 639 frontend tests, 51 checker tests, all workspace static/build/coverage
gates, 72 browser journeys without retries, seven sample-contract checks, both image builds
and independent review. The local Compose verification stack is healthy; test data was cleaned up.

See the [dated triage report](.claude/docs/dependency-triage-2026-09-22.md) for individual PR
links, compatibility evidence, exact update versions and conditions for revisiting each
migration. This targeted pass is not a comprehensive JVM/container vulnerability scan.

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

Remaining Toadie proposal: design a reader for optional declared adoption entities. Toadie's
narrower key scopes and incremental change feed were declined upstream; they are not pending Covenant prerequisites. Adoption
blueprints are importable ontology definitions, so absence must remain valid.

Toadie **2.13.0** has since shipped both ontology extensions, so neither is blocked upstream
any more. Names to map: the blueprint `dataset`, with the service-side relations
`produces_datasets` and `consumes_datasets` and `system`, which a second connection mapping
can select with the existing backend; and the relation entities `api_adoption` (`consumer` to
service, `api` to api, optional `environment`, plus `major_line`, `status`, `declared_by`,
`verified_at`, `notes`) and `dataset_adoption`, which carries `contract_version` instead of
`major_line`. An absent `major_line` or `contract_version` means undeclared, so a reader must
keep treating adoption as unknown rather than inferring it. Toadie documents both in its own
`.claude/docs/ontology.md`, with sample rows under
`sample-data/port/commerce-payments/entities/`. The dataset mapping setup is implemented in **1.0.5**:
explicit API/dataset presets, bilingual setup guidance, and backend/browser regression coverage.
Use two connections to the same instance, one per mapping; each ODCS contract can link multiple
datasets from its dataset connection. Database dependencies never imply dataset usage.
See [the setup instructions](.claude/docs/toadie-integration.md#configure-odcs-dataset-usage).
The optional adoption reader remains future work; no Toadie code changes were required.

## Registry synchronization from Toadie

Version **1.0.0** adds opt-in one-way metadata synchronization for selected Port domains,
systems and teams: explicit import/link preview, stable local IDs, source-owned metadata,
automatic refresh and visible missing/conflict states. System placement follows linked
source domains with an explicit fallback for domainless systems. Local team rosters,
permissions, contract assignments and local-only registries remain supported. No Toadie
code changes are required. See [the design and operating rules](.claude/docs/toadie-registry-sync.md).

Verification passed: 473 backend tests with build, static, coverage and API conformance gates;
635 frontend tests with build/static/coverage gates; eight relevant browser journeys, including
registry import/link, refresh, source disappearance and detach; and independent review.
The app version and bilingual changelog are updated to 1.0.0.

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

- **Registry CRUD and page abstraction (checkup tier D).** The nine-point checkup that shipped
  as 0.5.1 identified a generic registry list/form abstraction collapsing Domains, Systems,
  Environments and Teams, roughly 430 duplicated lines, and deferred it to a pass of its own
  rather than folding it into that release. Registry synchronization (1.0.0) has since added
  source summaries and conflict states to those same pages, so the shape to factor out has
  grown since the estimate.

- **Apache Avro schema-evolution pass.** An Avro payload inside an AsyncAPI document is
  compared structurally by the checker's `@asyncapi/diff` pass today. A dedicated Avro
  `SchemaCompatibility` pass would judge reader and writer compatibility on the format's own
  terms: added fields with defaults, removed defaulted fields, type promotion, enum symbol
  supersets and union branch matching. Recorded as a later add in
  [contract-standards.md](.claude/docs/contract-standards.md).

- **Web building blocks not yet ported.** Listed at the bottom of [web/CLAUDE.md](web/CLAUDE.md)
  and confirmed absent from `web/src`: Toadie's URL-carried view-state hook and row quick-view
  drawer, and Lettuce's `safeBackParam` open-redirect guard, `StatusPill`, `MetaStrip`,
  `FormFooter`, `ListToolbar` and `useDiscardGuard` for dirty-form navigation. Port one when a
  feature needs it, never speculatively. That same list has drifted in one place: it still
  names Lettuce's flat teams pages although Covenant has had `pages/Teams.tsx` and
  `pages/TeamDetails.tsx` since the teams registry landed.

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
