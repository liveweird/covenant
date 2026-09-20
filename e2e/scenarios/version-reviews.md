# Optional version reviews

- **Spec**: [tests/version-reviews.spec.ts](../tests/version-reviews.spec.ts)
- **Actors**: seed administrator as contract writer; a throwaway regular collaborator.
- **Owns**: one `e2e-reviews-*` domain/system/team/contract/version and one `e2e-reviewer-*`
  user, with a second version for pagination. Published versions are retired before normal API cleanup. No existing sample or
  Toadie data is modified.

## Scenario: collaborators review proposed content and an owner publishes independently of decisions

1. The owner opens a draft, proposes it and requests review.
   - Expected: no request action in DRAFT; a PROPOSED version has an open review. The
     requester has no approval action for their own request.
2. A regular collaborator opens the version, adds a comment and approves it.
   - Expected: discussion and approval persist; the collaborator cannot edit the document.
3. The owner changes the document through the editor and saves it.
   - Expected: the previous round is outdated; its discussion remains readable.
4. The owner requests a new review, and the collaborator requests changes with an explanation.
   - Expected: the fresh round records the new feedback independently of the older approval.
5. The owner activates the version despite requested changes.
   - Expected: publication succeeds, the round closes, its feedback remains visible and its
     composer is absent. The Reviews region passes the accessibility scan and fits a mobile
     viewport; desktop and mobile screenshots are captured.
6. Cleanup retires any published fixture version and removes the owned hierarchy and user.

## Scenario: review history remains accessible across rounds and discussion pages

1. Seed six rounds on an owned second version, withdrawing the first five and leaving the
   last open; add eleven comments to that open round through the API.
2. Open the version in the browser.
   - Expected: newest discussion appears first and both round and discussion pagination
     offer named controls. The Reviews region with both pagers passes accessibility checks.
3. Page the discussion, then the rounds.
   - Expected: the oldest comment and historical round are reachable. Request review remains
     unavailable even when the existing open round is on another page.

## Not covered here

Backend tests cover stale/ABA submissions, concurrent writes and complete permission/status
matrices. Frontend tests cover failed loads, stale displayed content and creation from a cached history page.
