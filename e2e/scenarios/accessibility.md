# Accessibility smoke (axe, WCAG A/AA)

- **Spec**: [tests/accessibility.spec.ts](../tests/accessibility.spec.ts)
- **Actors**: the seed administrator (`admin@covenant.local`) for the authenticated pages; an
  anonymous visitor for the login screen
- **Owns** (exclusive server-side state): one fixture contract with its domain, system and team
  (unique `e2e-axe-*` names), created through the API before the detail-page block and deleted
  after it; the list/form pages and the login screen are read-only

This is the registered template-title exception (see README.md): one `test()` per page is
generated from a list, so a single scenario section stands in for each list.

## Scenario: login screen has no WCAG A/AA violations

1. An anonymous visitor opens `/login` and waits for the sign-in form.
   - *Expected*: an axe scan (WCAG 2.0/2.1 A+AA, `color-contrast` included) reports zero
     violations.

## Scenario: `<path>` has no WCAG A/AA violations

1. The admin signs in, opens `<path>`, and waits for its heading.
   - *Expected*: an axe scan (same tags) reports zero violations.

The list today includes `/contracts/infer` (the Infer page's type picker, sample list and Paste
tab) alongside the catalog and registry pages — see `tests/accessibility.spec.ts`'s `AUTHED_PAGES`
list for the current set.

## Scenario: `<detail page>` has no WCAG A/AA violations

1. Before the block, the admin's API session seeds a domain, a system, a team and a team-owned
   OpenAPI contract with one draft version.
2. The admin signs in and opens the page — the contract page, the version page in Source and in
   Reader view, the edit-contract, new-version and compare pages, the team page, their own edit-user
   and user-features pages — and waits for its settled element (the heading, the document textbox,
   the reader's operation card).
   - *Expected*: an axe scan (same tags) reports zero violations.
3. After the block, the API session deletes the contract, the system, the domain and the team.

## Scenario: the reset-password page has no WCAG A/AA violations

1. An anonymous visitor opens `/reset-password` and waits for its heading.
   - *Expected*: zero violations.

## Scenario: the not-found page has no WCAG A/AA violations

1. The admin opens an address that matches no route.
   - *Expected*: the not-found page renders inside the shell with zero violations.

## Scenario: `<overlay>` has no WCAG A/AA violations

1. The admin opens the overlay — the notifications drawer from the header bell, the Try it drawer
   from the fixture version's page (no environment exists, so it shows its empty state), the New
   domain editor modal from the Domains page — and waits for the dialog.
   - *Expected*: an axe scan scoped to the dialog reports zero violations (focus trap, `aria-modal`,
     labelled close button, contrast).

## Not covered here (and why)

- **Interactive journeys mid-flight** (a Save-anyway modal with findings, the one-time password
  reveal, a confirm mid-transition) — those need the journey that produces them; the reader region
  after a real publish is scanned inline by `contracts.spec.ts`.
- **Colour tokens themselves** — the ratios are pinned in `web/src/theme.test.ts`; axe here
  verifies the rendered pages honour them.
