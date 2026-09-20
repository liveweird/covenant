# Release-line lifecycle plans and retirement impact

- **Spec**: [tests/lifecycle-plans.spec.ts](../tests/lifecycle-plans.spec.ts)
- **Actors**: the seed administrator acting as contract writer
- **Owns** (exclusive server-side state): one `e2e-lifecycle-*` domain, system, team and
  OpenAPI contract with 1.x and 2.x lines; one `e2e-lifecycle-toadie-*` encrypted connection
  to a test-owned GraphQL fixture. Cleanup retires published fixture versions, deletes the
  contract hierarchy and only that connection, and closes the fixture server.

## Scenario: an owner records and reloads a lifecycle plan with a replacement release line

1. Open the 1.x policy for a contract that also has a 2.x release line.
2. Enter planned deprecation and support-end dates, migration instructions, and choose the
   same contract's 2.x line as the replacement.
3. Save and reload the contract page.
   - *Expected*: both dates, the migration instructions, and the 2.x replacement persist.
   - *Expected*: the dates remain advisory; the deprecated 1.0.0 version does not transition.

## Scenario: retirement reviews declared consumers and requires explicit acknowledgement

1. Open Retire for deprecated 1.0.0 after its contract has linked two APIs from the owned
   Toadie fixture.
   - *Expected*: the impact dialog shows the Storefront consumer, Commerce system, Retail team,
     Unknown version/release-line values, and the contract-level usage limitation.
   - *Expected*: the modal has no WCAG A/AA violations; its screenshot is attached.
2. Cancel without acknowledging.
   - *Expected*: 1.0.0 remains deprecated.
3. Open Retire again, explicitly acknowledge the declared usage and its limitations, then retire.
   - *Expected*: 1.0.0 transitions to retired.

## Not covered here (and why)

- **Live scheduled reminders** — the production scheduler scans hourly. Waiting for it would make
  the browser suite slow and timing-sensitive; deterministic backend tests cover UTC threshold
  selection, overdue catch-up and recipient-scoped deduplication.
