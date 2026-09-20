# Parallel release lines

- **Spec**: [tests/release-lines.spec.ts](../tests/release-lines.spec.ts)
- **Actors**: the seed administrator, without changing that account
- **Owns** (exclusive server-side state): one API-seeded `e2e-lines-*` domain, system,
  team and OpenAPI contract with versions across 1.x and 2.x. Cleanup retires only this
  fixture's published versions, then deletes its contract and registries through the API.

## Scenario: an owner publishes a maintenance backport while a newer major remains active

1. Open a contract with deprecated 1.9.0 and active 1.10.0 and 2.0.0 versions.
   - *Expected*: separate 1.x and 2.x release lines recommend 1.10.0 and 2.0.0.
2. Start a new version from the 1.x line, enter 1.9.1 and its OpenAPI document.
   - *Expected*: the live check compares against published 1.9.0, even though it is
     deprecated; neither the newer minor nor the newer major prevents saving.
3. Save, propose and activate the backport.
   - *Expected*: 1.9.1 is active, while both release lines and their automatic
     recommendations remain intact.
4. Filter the versions table to 1.x.
   - *Expected*: the backport is present and 2.0.0 is absent from the filtered table.

## Scenario: an owner manages support and a recommendation independently for each release line

1. Edit the 1.x policy, select Maintenance, enter a support-end date and policy notes,
   and explicitly recommend the active stable 1.9.1 backport.
   - *Expected*: the saved line displays the policy and recommendation; 2.x is unchanged.
2. Reload the page.
   - *Expected*: policy and explicit recommendation persist.
3. Inspect the contract history and run an accessibility scan of the release-lines region.
   - *Expected*: the policy change is visible and there are no WCAG A/AA violations.

## Scenario: ending release line support requires impact acknowledgement and does not retire its contract versions

1. Change 1.x to End of life and save the policy.
   - *Expected*: the retirement-impact review opens instead of changing support immediately.
2. Acknowledge the declared-usage limitations and click End support.
   - *Expected*: 1.x has no recommended version; 2.x still recommends 2.0.0.
3. Open version 1.9.1.
   - *Expected*: the version is still active, with its Deprecate action available.

## Not covered here (and why)

Backend and frontend tests cover reader permissions, invalid recommendations, malformed dates,
duplicate SemVer precedence, concurrent writes, paging, import parity and empty release lines.
