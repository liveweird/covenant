# Version review and discussion (0.13.0)

Reviews are optional collaboration on a PROPOSED version. They never gate publication,
change ownership, assign reviewers, or imply an approval quota. There is no CI/CD or Toadie
dependency. The version page exposes Reviews alongside the stored source/reader and findings.

Verified layouts: [desktop](screenshots/version-reviews-desktop.png) and
[mobile](screenshots/version-reviews-mobile.png). The screenshots use disposable
browser-test records and show history after publication.

## Permissions and lifecycle

- Any authenticated user can read review history for an active contract/version.
- A current contract writer (owner, owning-team member, ADMIN) can request review only while
  the version is PROPOSED and no open review exists.
- Active authenticated users can comment on an open review. Any such user except its
  requester can record approval or request changes. ADMIN does not bypass this self-review
  restriction. Decisions grant no contract-editing permission.
- Comments and changes requested require a nonblank explanation; approval may have one.
  Text is trimmed, limited to 4,000 characters and rendered as plain text.
- Each submission is an immutable entry. A user's latest decision in a round supersedes
  their earlier decision for counts; adding a comment does not erase their decision.
- Editing/synchronizing different document bytes closes the open round with
  `CONTENT_CHANGED`. Leaving PROPOSED closes it with `WITHDRAWN` (DRAFT) or `PUBLISHED`
  (ACTIVE). A closed round never reopens, even if the version is proposed again unchanged.
- Closed rounds and entries remain readable but accept no further submissions. Request a
  new review on the current PROPOSED content to continue. Publishing is allowed with no
  review, no approvals, or outstanding requests for changes.

Counts are individual decisions, not an overall approval verdict. Historical counts remain
visible with the round's closure reason and content relevance. A published review is a
record of feedback, not proof that a quorum or governance policy was satisfied.
Round `status` is `OUTDATED` when its content revision differs, otherwise `CLOSED` when it
has a closure timestamp, and `OPEN` otherwise. `isCurrentContent` states content relevance
independently of closure: a published round may remain about the current content.

## Content identity and concurrency

V19 adds `contract_versions.content_revision`, starting at 1. The detail DTO exposes
`contentRevision`. The shared content store path increments it only on a successful
byte-changing PUT or sync. Identical-content saves, source-reference changes, rechecks and
lifecycle transitions leave it unchanged. An A → B → A edit therefore has three distinct
revisions; approvals of the first A cannot become current again.

Each round captures its revision and SHA-256. New requests and entries send
`expectedContentRevision` from the document the user is viewing. The server checks it inside
the committing transaction and returns 409 for stale content or a closed round. The SPA
does not silently substitute a newer revision while still showing an older document.

All review, content and lifecycle writes serialize on the existing active parent-contract
lock. Writer authorization is repeated inside the transaction. Review entry writes lock the
parent, re-read the active version and round, and then validate permissions and state.
Closure and content/lifecycle changes commit together. A unique open-round constraint is an
additional duplicate-request guard. Invalid text or rejected writes change no revision or
review state.

The stored hash/revision identifies the reviewed content, but this release does not retain
copies of old document bodies or offer a historical-source viewer. Review history preserves
the discussion and decisions, not a complete source-control history.

## API and storage

- `GET /api/v1/contracts/{id}/versions/{vid}/reviews`: paged rounds, plus the caller's
  `canRequest` and current content revision.
- `POST` to that collection: request a review of `expectedContentRevision`; 201 with Location.
- `GET /api/v1/version-reviews/{rid}`: one round, including closure information, content
  relevance, latest-decision counts and caller permissions.
- `GET /api/v1/version-reviews/{rid}/entries`: paged immutable entries.
- `POST` to the entries collection: append `COMMENT`, `APPROVED` or `CHANGES_REQUESTED`,
  with the expected revision and optional/required body; 201 with Location.
- `GET /api/v1/version-reviews/{rid}/entries/{entryId}`: the immutable entry addressed by
  its creation Location, with active-parent and round-membership checks.

Lists use shared pagination and strict sort/query parsing. Missing or soft-deleted parents
are 404. Request authorization precedes body validation; requester decisions are 403,
stale/state conflicts 409, and malformed input 400. All routes require authentication.

Review rounds and entries are retained history/detail records, with no delete or edit API.
They are hidden when the parent contract/version is soft-deleted. Names and deleted-user
markers are read-only public identity summaries; no ADMIN user-directory access is needed.

Structural events use `ContractActivity.record` after commit and notify contract followers,
excluding the actor. Discussion text never enters security audit or notification parameters.
The existing separate-transaction activity failure model applies: an activity failure can
return an error after the review mutation committed; reload before retrying. There are no
assigned reviewers or direct review-request emails in this release.

Deploy the backend and SPA together. V19 is additive, but once new review event/notification
types are stored, an older binary may not decode them. A rollback requires compatible readers
or restoring the pre-release database, rather than merely switching the container image.

## Verification

Backend regressions cover permissions, lifecycle, revision/no-op/ABA behavior, stale requests,
entry validation, latest decisions, pagination, missing/deleted parents and publication without
approval. UI tests cover permissions, outdated/closed history, errors, paging and stale-view
protection. The `version-reviews` Playwright journey exercises owner request, collaborator
feedback, changed-content invalidation, a fresh round and publication with requested changes.
