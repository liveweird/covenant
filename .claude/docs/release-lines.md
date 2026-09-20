# Parallel release lines

Covenant groups versions into major-derived lines: `0.x`, `1.x`, `2.x`. A line belongs to
one contract. Minor branches are not separate managed resources, but backports such as
`1.9.1` after `1.10.0` and `2.0.0` are allowed. Publication order does not define SemVer
precedence. Published documents remain immutable and are never rewritten by this feature.

## Version identity and compatibility

A contract has at most one nondeleted version at each SemVer precedence. Build metadata is
stored verbatim but is not a way to create another version: `1.2.3+a` conflicts with `1.2.3+b`.
Deleting an eligible draft releases its identity. The parent-contract lock and a database
unique index enforce the invariant, including concurrent writes and imports.

For a candidate, automatic compatibility checks choose the highest ACTIVE or DEPRECATED
version strictly below it in the same major. If none exists, use the highest eligible version
in a lower major. A higher minor cannot be the baseline for a backport; drafts, proposals,
retired versions and higher majors cannot be baselines. Deprecated published versions remain
eligible because consumers may still depend on them. A cross-major comparison keeps the
existing informational treatment of intentional major-version breaks. Explicit comparisons
between two selected versions remain available regardless of these defaults.

Create, edit, sync, recheck, import, live checks and implicit compatibility use the same
selection rules. A live check without a candidate version uses the highest eligible published
version. Slow checks run outside the write transaction; the committing transaction checks the
selected baseline again under the parent lock. Support-policy and recommendation changes do
not change the compatibility baseline.

## Support and recommendations

Lines are created atomically with their first version. Existing catalog versions are backfilled
without inventing support commitments. New and migrated lines start with `UNSPECIFIED` support,
no support-end date or policy, and automatic recommendation selection.

| Field | Meaning |
| --- | --- |
| `supportStatus` | `UNSPECIFIED`, `SUPPORTED`, `MAINTENANCE`, or `END_OF_LIFE` |
| `supportEndsOn` | Optional ISO calendar date, advisory only |
| `supportPolicy` | Optional bounded plain-text support explanation |
| `recommendedVersionId` | Explicit stable ACTIVE version in this contract and major, or null for automatic selection |

Automatic selection recommends the highest ACTIVE stable version in the line; it excludes
prereleases and all other lifecycle states. A pinned recommendation can select an older stable
ACTIVE release deliberately. Moving that version out of ACTIVE clears the pin in the same
transaction and restores automatic selection. `END_OF_LIFE` lines have no effective
recommendation and must have a null pin.

The support-end date does not automatically change support status, version lifecycle,
recommendations or write permissions. Setting END_OF_LIFE does not retire versions or prohibit
recording further versions. The UI exposes the support declaration separately from the
recommendation; an UNSPECIFIED line can have a recommendation without implying a support promise.
None of these fields claims that a version is deployed anywhere.

The contract's existing `latestVersion` remains the highest nondeleted catalog version,
including drafts. Display it as **Highest version**, separately from line recommendations.
Line summaries use full SemVer precedence, not lexicographic ordering of prerelease text.

## API, permissions and history

- `GET /api/v1/contracts/{id}/release-lines`: paged line summaries, default descending major.
- `GET /api/v1/contracts/{id}/release-lines/{major}`: one line, including its policy,
  explicit pin and effective recommendation.
- `PUT` the same resource: full replacement of the policy and lifecycle-plan fields, returning `204`.
- The existing versions collection accepts a scalar `major` filter, including `0`.

Every authenticated reader can inspect lines. Policy writes require the existing contract
writer permission, checked before request-body validation and again under the committing
parent lock. No public create/delete-line operations are needed: lines follow version creation,
retain their metadata when their last draft is deleted, and disappear with a deleted contract.
An empty retained line has a zero count and null highest/recommended version.

Policy changes emit a security audit event and use `ContractActivity.record` for structural
history and follower notifications. Idempotent unchanged PUTs do not produce duplicate events.
This follows the existing post-commit activity model; there is no new approval workflow.

## Verification and rollout

Backend regression coverage must include backports across both major and minor releases,
deprecated baselines, duplicate precedence, concurrent creates, unauthorized writes,
recommendation constraints, pin invalidation, empty lines and migration defaults. The SPA
must support older lines beyond the first page, preserve user-entered documents, and expose
read-only policy information without write controls. The Playwright journey and scenario are
`e2e/tests/release-lines.spec.ts` and `e2e/scenarios/release-lines.md`.

The migration is additive; never edit existing migrations. Once new history/notification enum
values are stored, rolling back to an old binary is unsafe. A rollback needs a compatible binary
or restoration of a database snapshot taken before rollout.

## Planned deprecation and retirement (0.11.0)

A line can carry `deprecatesOn` (canonical ISO calendar date), `replacementContractId`,
`replacementMajor`, and `migrationGuide` (trimmed plain text, at most 8,000 characters).
`supportEndsOn` remains the single support-end date. Both dates may be in the past; when both
are present, deprecation must be on or before support end. Omitted optional PUT fields clear
values. These dates are advisory: they never transition versions, change support status,
clear recommendations, alter compatibility baselines, or restrict writes automatically.

A replacement may be another contract as a whole or an existing major line. The same contract
requires a different major; direct self-replacement is invalid. Assigning a new reference
requires an active target, but no write permission on that target. The response's `replacement`
summary preserves the IDs and indicates `available=false` after target deletion. An unchanged
unavailable reference can be retained while editing other fields. Targets are advisory links:
there is no recursive resolution or cross-contract locking. Missing newly selected targets are
invalid input, not a missing source resource.

The release-line card previews migration instructions; its retirement-impact dialog displays
them in full. The same dialog precedes an explicit version retirement or a change to
END_OF_LIFE. It shows cached **contract-level** consumers from the existing Toadie APIs,
including systems, teams, freshness, and unavailable mappings. It does not infer which major
or version a consumer uses. No declared consumers is never proof that retirement is safe.
Missing/stale/failed usage reads are warnings an owner can acknowledge; failure to load the
Covenant plan must be retried before confirming. Readers may inspect the impact without write
controls. Ending support still does not retire individual versions; existing transition API
semantics and writer guards remain unchanged.

## Deadline notifications

Followers receive in-app reminders for both deprecation and support end. The scheduler runs
on startup and hourly, evaluating calendar dates in UTC. It selects only the current urgency:
8–30 days remaining, 1–7 days remaining, or the deadline reached (including overdue). A restart
or newly entered past date catches up with one current reminder per deadline, not every missed
window. Existing support-end dates become eligible on upgrade. END_OF_LIFE lines, deleted
contracts/lines, retained lines without versions, and deleted users are excluded. New followers
may receive the current reminder window; these system notifications have no actor exclusion.

V18 adds an internal nullable notification deduplication key. Reminder insertion and its
recipient-scoped unique key commit in one database transaction after locking and re-reading
the active source contract/line. The key includes line ID, deadline kind, date, and window
(30/7/0); today and overdue share window 0. Concurrent scans, service restarts, deleting a
notification, or clearing/restoring the same date cannot duplicate it. Changing a date creates
a new occurrence. Ordinary policy changes retain the established post-commit ContractActivity
history and notification behavior; scheduled reminders do not create fake user history events.

Candidate scans use bounded keyset batches, then revalidate each line under the parent lock.
Per-line failures do not stop later reminders; failed transactions remain eligible for retry.
`LIFECYCLE_REMINDERS_ENABLED=false` disables the worker; normal backend tests disable it and
exercise `ReleaseLineReminderService` with a fixed UTC clock explicitly. No Toadie call or
account/team mapping is needed to deliver reminders to Covenant followers.
