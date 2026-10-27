# Toadie contract resolver — the way in from Toadie

- **Spec**: [tests/toadie-resolver.spec.ts](../tests/toadie-resolver.spec.ts)
- **Actors**: the seed admin (`admin@covenant.local`).
- **Owns** (exclusive server-side state): one GraphQL fixture of its own carrying two entities
  it adds under a UNIQUE identifier, two Covenant connections to that fixture, and two contract
  hierarchies (one OpenAPI, one ODCS) with their domains, systems and teams. It removes all of
  them through the ordinary APIs and never changes the live Toadie catalog.

`/toadie/{blueprint}/{identifier}` is a **public, stable contract between the two apps**: Toadie
hard-codes it in a calculated blueprint property, so this suite is the end-to-end guard on that
URL continuing to work. It adds its own entity under one unique identifier beneath BOTH the `api`
and `dataset` blueprints on purpose, so the blueprint filter is proven rather than assumed.

**Why a unique identifier and not the fixture's stock `orders`:** the reverse read is keyed on
(blueprint, identifier) alone — nothing scopes it to a connection or a contract. Sharing an
identifier with `toadie-usage` or `lifecycle-*` on another worker makes the lookup return two
matches, so the page renders its chooser and the single-match redirect this suite exists to prove
never happens. That is a test-isolation hazard, not a product defect.

Setup links the contracts through the API; the link editor itself belongs to
[toadie-datasets.md](toadie-datasets.md).

## Scenario: a Toadie entity URL lands on the contract that covers it, per blueprint

1. Sign in as the admin, then visit the contracts list so there is a previous page to return to.
2. Open `/toadie/api/{entity}` — the URL Toadie renders on its `api` entity.
   - *Expected*: the browser ends on the OpenAPI contract's page, showing its name as the page heading.
3. Press Back.
   - *Expected*: the contracts list, NOT the resolver — the redirect replaced the resolver in the
     history, so returning to Toadie is one step from the contract.
4. Open `/toadie/dataset/{entity}` — the same identifier under the other blueprint.
   - *Expected*: the ODCS contract's page, not the OpenAPI one. The match is keyed on the
     connection's target blueprint, never on the identifier alone.
5. Open `/toadie/api/{unlinked}` — an identifier no contract covers.
   - *Expected*: the resolver page itself, with its "no contract is linked yet" empty state.
     Not an error, not the 404 page. A screenshot is attached.
6. Open `/toadie/api/{unlinked}-missing` — an identifier that exists under no blueprint at all.
   - *Expected*: the same empty state; an unknown entity and an unlinked one read alike.

## Scenario: an unauthenticated entity URL survives the sign-in round trip, percent-encoding included

1. Clear the stored session, then open `/toadie/api/team%2F{unlinked}` — someone following a link
   from Toadie without a Covenant session, for an identifier whose own name contains a slash.
   - *Expected*: the login page.
2. Sign in.
   - *Expected*: the browser returns to `/toadie/api/team%2F{unlinked}` with the percent-encoding
     intact — the saved destination keeps the full path, so the entity is not lost at the door.
3. Read the resolved entity on the page.
   - *Expected*: the empty state for `api`, and the identifier shown as `team/{unlinked}` — one
     identifier, decoded exactly once. Had the encoded `/` been treated as a path separator the
     lookup would have asked for `team` instead.

## Not covered here (and why)

- **Editing links.** Owned by [toadie-datasets.md](toadie-datasets.md) and
  [toadie-usage.md](toadie-usage.md); this suite links through the API so the journey under test
  is only the resolver.
- **Several matches on one entity.** Needs two contracts linked to the same entity through two
  connections; the rendering is covered by `web/src/pages/ToadieLinkResolver.test.tsx` and the
  query by `ToadieLinkResolveTest`, so the e2e cost is not worth repeating here.
- **Toadie's own side.** The calculated blueprint property lives in Toadie's repository; Covenant
  only guarantees the URL shape it answers.
