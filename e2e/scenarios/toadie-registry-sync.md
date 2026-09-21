# Toadie registry synchronization

- **Spec**: [tests/toadie-registry-sync.spec.ts](../tests/toadie-registry-sync.spec.ts)
- **Actors**: seed admin; ordinary authenticated reader.
- **Owns**: one fixture GraphQL server, connection, uniquely named domains/systems/team,
  a team member and a contract with an Environment. Existing demo records are untouched.

## Scenario: an administrator previews and links Port registries while retaining local ownership

1. Create an ordinary Toadie connection with registry synchronization disabled. Open its sync
   action and follow the setup prompt to the editor's expanded registry mapping section.
   Explicitly enable synchronization with flat domains and selected description properties.
   - Expected: no misleading snapshot error or endless loading state before setup.
2. Browse domain candidates, link a selected source domain to an existing local domain, preview
   the before/after metadata and apply.
   - Expected: the local ID is preserved; the source parent remains informational.
3. Link the existing local system through preview and apply, following its linked source domain.
   Import a separate domainless source system with an explicitly selected fallback domain.
4. Link the source team to an existing local team with a member and an owned contract.
   - Expected: metadata follows Toadie and source status is visible, but the member, contract
     owner and write permission are unchanged.
5. Open the team registry as a reader and inspect source information. Check that reader page's
   accessibility and save a screenshot.

## Scenario: refresh updates linked metadata and preserves records when a source disappears

1. Rename a linked source domain, system and team in the test-owned upstream.
2. Refresh the connection using the public API and observe the updated names in the SPA.
   - Expected: local IDs, memberships, contract assignment and Environment are preserved.
3. Remove a source team from the upstream and refresh again.
   - Expected: its local team and roster remain, the source is marked missing, and the prior
     metadata remains visible.
4. Detach the source through the registry UI and edit the local team metadata.
   - Expected: ordinary local editing becomes available with the same team ID and roster.

## Not covered here (and why)

Stale preview tokens, conflicting names/placement, concurrent writers, malformed mappings,
backend authorization denial and full-scan budgets have focused backend regression tests.
