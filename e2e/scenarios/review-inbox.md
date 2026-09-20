# Review inbox

- **Spec**: [tests/review-inbox.spec.ts](../tests/review-inbox.spec.ts)
- **Actors**: seed administrator as writer; a disposable regular follower/reviewer.
- **Owns**: one `e2e-inbox-*` domain/system/empty owning team/contract/version and one
  `e2e-inbox-reviewer-*` user, their subscriptions and review rounds. Cleanup retires the
  published version and deletes owned fixtures through normal APIs. Existing data is preserved.

## Scenario: personal review scope respects follows and opens current document feedback

1. Seed a proposed version and an open review, with a regular follower.
2. Open the administrator's default inbox, then select All contracts.
   - Expected: ADMIN's write override does not imply personal ownership; the catalog scope
     shows the fixture once.
3. Sign in as the follower and open Awaiting my review.
   - Expected: the request appears. The page passes accessibility checks and does not overflow
     a mobile viewport; capture desktop/mobile screenshots.
4. Follow the row's Review link.
   - Expected: the current document opens and scrolls to Reviews after loading.
5. Comment, revisit the awaiting view, then approve the document.
   - Expected: a comment leaves the request awaiting a decision; approval removes it from
     awaiting while retaining it in the unfiltered followed inbox.
6. Unfollow and refresh.
   - Expected: the fixture disappears from Followed.

## Scenario: feedback and closed rounds guide follow-up without retaining superseded requests

1. Replace the collaborator's approval with a request for changes; let the administrator follow.
   - Expected: the request appears in the administrator's Changes requested view.
2. Edit the content, then request a new review.
   - Expected: the closed round moves to Needs new review, then disappears from that attention
     group when superseded; the unfiltered inbox has exactly one row for the new round.
3. Withdraw and re-propose unchanged content.
   - Expected: the latest closed round needs a new review despite unchanged content.
4. Request another round and publish.
   - Expected: the version leaves every inbox scope; publication requires no approval.

## Not covered here

Backend tests cover team membership changes, multiple proposed versions, complete latest-decision
and filter validation matrices, and pagination/summary consistency. UI tests cover failed loads,
query-key isolation and review mutation invalidation.
