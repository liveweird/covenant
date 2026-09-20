# Review inbox (0.14.0)

The Review inbox at `/reviews` collects existing review work across the catalog. It is a
read-only view over [version reviews](version-reviews.md), not an assignment or approval
workflow. Open a row to read the current document and use its Reviews section. Publication
remains an explicit writer action, independent of review decisions.

## Membership and attention

One row represents the latest review round (highest review ID) of an active PROPOSED version.
Each proposed version is independent, including older major release lines. Versions without
any review request do not appear. Deleted versions/contracts/systems/domains and versions in
other lifecycle states are excluded. A newer round replaces the earlier round in the inbox;
all rounds remain in version history.

The default **Relevant to me** scope (`RELATED`) is the union of contracts owned personally,
owned by a team the caller currently belongs to, or followed by the caller. `OWNED` selects
personal/team ownership, `FOLLOWED` selects subscriptions, and `ALL` selects the catalog.
ADMIN write permission does not make every contract personally owned. Membership and
subscriptions are evaluated from current data; scopes do not assign responsibility.

- **Awaiting my decision**: the round is open on current content, someone else requested it,
  and the caller has not recorded a decision. Comments do not count as decisions.
- **Changes requested**: an open current round has at least one reviewer's latest decision
  requesting changes. A later approval supersedes that person's request; comments do not.
- **Needs new review**: the latest round is closed while the version is PROPOSED. This
  includes editing and withdrawal followed by re-proposal, even with unchanged content.

With no attention filter, all candidates appear, including requests the caller created or
already decided on. Attention groups can overlap. Summary counts retain scope and search,
but ignore the selected attention group, so another group remains reachable. Total counts
distinct candidate versions rather than adding the overlapping groups.

## API and implementation

- `GET /api/v1/version-reviews/inbox`: standard `{items, page, pageSize, total}` envelope.
- `GET /api/v1/version-reviews/inbox/summary`: `total`, `awaitingMyReview`,
  `changesRequested`, and `needsNewReview` counts.
- Both require authentication and accept `q`, `scope`, and `attention`. Search matches
  normalized contract names or version strings. Summary validates but lifts `attention`.
- List sorting accepts `id`, `contractName`, and `requestedAt`. The default is oldest
  request first, with ID as a stable tie-breaker. Shared strict scalar parsing rejects
  malformed or repeated filters.

The row ID is the review ID; nested contract/version IDs identify the document link. Rows
contain identity, ownership, latest-decision counts and caller context, never document or
comment bodies. Filtering/counting happens in SQL and page facts are batched. No migration,
background worker, stored inbox state, Toadie call, or new mutation endpoint is needed.

The SPA keeps caller identity in inbox query keys. Review submissions invalidate the inbox;
existing contract/content/lifecycle mutations invalidate their shared contract-query prefix.
Refresh reloads both rows and counts. A row opens the version's `#reviews` anchor after its
asynchronous load. Existing review actions use the loaded document revision and current
server permissions, never an inbox snapshot as authority.

Verified layouts: [desktop](screenshots/review-inbox-desktop.png) and
[mobile](screenshots/review-inbox-mobile.png), using disposable browser-test records.

## Verification

Backend regressions cover current ownership/follows, ADMIN scope, latest-round/decision
selection, lifecycle and deletion exclusions, summary consistency and strict paging/filter
validation. UI tests cover filters, loading/errors, caller cache isolation, mutation
invalidation and deferred anchor navigation. The `review-inbox` Playwright journey verifies
personal scope, feedback, fresh-round discovery and document navigation against the stack.
