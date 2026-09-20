# Product backlog

Updated 2026-09-20. These are proposals, not delivery commitments. Implemented behavior is
documented in [README.md](README.md) and the release changelog.

## Proposed next steps

1. **Version review and discussion.** Let teams request review of a PROPOSED version, discuss
   findings, and record approval or requested changes against the reviewed content. Content
   edits make prior approvals outdated. Start with optional review; enforcement and reviewer
   permissions need an explicit design decision. Keep this in Covenant's UI.
2. **Shareable migration and impact reports.** Export a release line's dates, replacement,
   migration guidance and declared affected services/teams for planning discussions. Include
   observation timestamps, missing/stale usage warnings, and the limit that exact version
   adoption is unknown. Reuse the existing Toadie integration; no Toadie changes are expected.

## Deferred

Related cross-project proposals are collected in the
[Toadie implementation handoff](.claude/docs/toadie-handoff.md). They are optional improvements
to the existing integration and have their own implementation scope.

- **Connecting Covenant to teams' CI/CD pipelines.** Parked at the user's request because the
  team is not ready to adopt it. Revisit when the team wants pipeline checks. The proposal
  includes checking a candidate against a selected contract/major line, machine-readable
  validation and compatibility results, a small CLI/workflow example, and scoped automation
  credentials. Publication would remain explicit. This deferral concerns the product
  integration; Covenant's own build, test and deployment workflows remain in use.

## Recently completed

- Catalog-wide lifecycle overview, deadline/plan attention and cached contract-level usage (0.12.0).

- Parallel supported major release lines and backports (0.9.0).
- Declared contract usage from Toadie's Port ontology (0.10.0).
- Deprecation/support-end planning, replacement and migration guidance, follower reminders,
  and consumer-impact review before retirement or end of support (0.11.0).
