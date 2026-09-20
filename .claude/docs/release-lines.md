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
- `PUT` the same resource: full replacement of the four policy fields, returning `204`.
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
