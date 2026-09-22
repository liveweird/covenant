# Catalog lifecycle overview

- **Spec**: [tests/lifecycle-overview.spec.ts](../tests/lifecycle-overview.spec.ts)
- **Actors**: the seed administrator acting as contract writer
- **Owns** (exclusive server-side state): one API-seeded `e2e-overview-*` domain,
  system, team and OpenAPI contract with three DRAFT major release lines, plus one
  `e2e-overview-toadie-*` connection to an owned GraphQL fixture. Cleanup deletes
  only this contract, its registries and connection, then closes the fixture.

## Scenario: an owner triages release lines and completes a migration plan from the overview

1. Open Lifecycle and filter by the owned contract name.
   - *Expected*: one row appears for each of its 1.x, 2.x and 3.x release lines.
   - *Expected*: the attention counts report one deadline in the next 30 days, one
     reached support-end date, one incomplete migration plan and three lines whose
     usage is unavailable or stale.
2. Select the reached-support-date count, then clear it.
   - *Expected*: the attention tile filters to 1.x and toggles back to all three lines.
3. Edit 1.x from the overview and add its missing migration instructions.
   - *Expected*: the existing policy modal saves the change; the overview reports
     the plan complete and the incomplete-plan count falls to zero.
4. Select the No dates set deadline filter and review 3.x impact.
   - *Expected*: only 3.x remains; link the owned fixture APIs before opening the review. The review explains that usage is contract-level
     and runtime adoption remains unknown, with explicit declarations shown separately.
5. Clear the deadline filter, scan the overview table with axe and capture a screenshot.
   - *Expected*: all three lines return with the same contract-level consumer count and the table has no WCAG A/AA violations.

## Not covered here (and why)

Provider/consumer refresh behavior belongs to `toadie-usage.spec.ts`; irreversible
version transitions and acknowledgement are exercised by `lifecycle-plans.spec.ts`.
