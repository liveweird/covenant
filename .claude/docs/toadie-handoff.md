# Toadie integration improvements: implementation handoff

Prepared 2026-09-20 for work in `~/Sources/toadie`. These proposals consolidate the limitations
identified while implementing Covenant's read-only integration. They are not prerequisites for
the working integration and are not all confirmed defects. Recheck Toadie's current branch
before implementation; another agent may be working there.

Checked against Covenant `b045683` and Toadie `ec7652a` (clean working tree at inspection).
Toadie already provides read-only GraphQL, revocable hashed integration keys, paged/filterable
ontology reads, effective ownership, computed properties and ontology health reports. Preserve
and extend those capabilities rather than rebuilding them.

## Ownership and scope

- Toadie owns architecture, services, systems, teams and declared relationships in the **Port
  ontology**. Use its existing generic blueprints/entities/relations; do not use the Backstage
  System Model for this integration.
- Covenant owns contract documents, validation, versions, release-line policies and retirement.
- Preserve existing integration queries and client keys. Introduce capabilities additively.
- Do not change the running local connection, revoke its key, or replace persistent sample data
  during tests. Use isolated fixtures. Keep keys out of reports and Git.
- CI/CD integration for teams is deferred. These improvements do not require it.

## 1. Consistent multi-page ontology reads — recommended first

**Observed limitation:** Covenant reads blueprint definitions, then API, service, system and
team entities in separate numbered-page requests. Stable totals and increasing IDs detect some
concurrent changes, but cannot detect all same-count edits or changes between entity types.
The result is an observation over time, not an atomic snapshot.

**Requested outcome:** give clients a way to obtain a consistent complete graph, or reliably
detect that the graph changed and restart. Choose a bounded snapshot/export or a revision
protocol with defined consistency guarantees. Cursor pagination alone is not sufficient.

**Acceptance:**

- The consistency boundary covers blueprint definitions, entities, relations and effective team
  ownership, including inherited ownership changes.
- Inserts, edits, deletes and renames between pages cannot silently produce a successful mixed
  revision. Include tests where total counts remain unchanged.
- Expired snapshots/revisions return a documented, distinguishable result; clients can restart.
- Tokens are bound to the authorized read scope; storage, lifetime and response sizes are bounded.
- Existing numbered-page queries keep working. Document precisely whether the new protocol
  returns a snapshot or merely detects invalidation.

**Covenant follow-up:** adopt the new protocol, retain the last complete cache on failure, and
keep freshness visible. A consistent graph still does not prove runtime adoption or safe retirement.

## 2. Narrower integration-client read permissions

**Observed limitation:** the integration key used by Covenant can read the whole Port ontology,
although Covenant requests only selected metadata for a small set of blueprints.

**Requested outcome:** let administrators issue credentials with explicit read permissions for
the required blueprint/entity metadata and relationships. Define whether blueprint allowlists
alone are sufficient or field-level restrictions are needed. Covenant currently requires IDs,
identifiers, titles, blueprint names, selected relations, effective teams and update timestamps;
it does not request arbitrary entity properties or creator details.

**Acceptance:**

- Enforce permissions on every relevant GraphQL entry point, including individual lookups,
  lists, counts and relationship traversal. Aliases/fragments must not bypass restrictions.
- Specify schema-discovery and cross-scope reference behavior. Do not disguise a denied scan
  as an authoritative empty graph; Covenant rejects dangling selected references.
- Scope changes/revocation take effect predictably and invalidate incompatible snapshot tokens.
- Document migration behavior for existing keys explicitly; do not silently break them.
- Test a minimal Covenant client against its actual queries, plus denied read cases.

**Covenant follow-up:** use a scoped key and, if needed, targeted blueprint queries. Its current
client lists blueprint definitions before fetching the four mapped entity types.

## 3. Explicit version/release-line adoption — optional domain extension

**Observed limitation:** a service's `consumes_apis` relation declares API usage, not the version
or major line it consumes. Covenant deliberately reports those values as unknown.

**Proposed model:** use a configurable Port usage/adoption entity related to the consumer service
and API. Store an explicit declared version or major line, with provenance and verification time.
Decide how parallel versions and environments are represented before implementing. Keep unknown
adoption valid; do not derive it from the API's current version or Covenant's recommendation.

**Acceptance:**

- A service can declare concurrent adoption of different major lines without overwriting data.
- Distinguish declared adoption from observed runtime evidence; retain who/what supplied it and
  when it was verified. Manual maintenance must work without CI/CD.
- Use existing generic ontology validation and ownership rules. Avoid adding a second hard-coded
  service registry or contract-version lifecycle to Toadie.
- Define how declarations coexist with `consumes_apis` and how conflicting/stale declarations
  are shown. Include rename, deletion, unknown-version and duplicate-declaration tests.
- Publish the blueprint/relation/property mapping and representative GraphQL fixtures.

**Covenant follow-up required:** a separately designed mapping and reader for these declarations.
Adding a Toadie blueprint alone will not populate Covenant's version/release-line fields.

## 4. Explicit dataset/data-contract usage — optional ontology configuration

**Observed limitation:** the default integration maps API relationships. An ODCS contract needs
an explicit dataset/data-contract mapping; a dependency on a database does not identify which
dataset contract a service consumes.

**Requested outcome:** provide a reusable Port blueprint template and example data for dataset
or data-contract entities, with explicit producer/consumer service relationships. First establish
whether Toadie's existing configurable ontology already supports the entire use case; prefer
configuration and documentation when no product code is necessary.

**Acceptance:** model two datasets in one database with different consumers; preserve their
distinct identities; document the relationship to multi-dataset ODCS contracts. Export enough
definitions and references to validate the mapping. Never infer consumption from database access.

**Covenant follow-up:** evaluate mapping compatibility. Current connection configuration selects
one target blueprint and one provides/consumes relation pair; simultaneous API and dataset
mappings may need a Covenant extension or separate connections.

## 5. Incremental refresh — lower priority

**Observed limitation:** Covenant periodically rescans the mapped ontology. Full refresh is
adequate today but costs more as catalogs grow.

**Requested outcome:** after the consistency protocol is settled, consider a revision-based
change feed with deletions and a documented full-resync fallback. Webhooks can be an optional
wake-up signal; they should not be the only source of truth.

**Acceptance:** support replay, duplicate handling, retention expiry, and deletion tombstones;
include blueprint/relation/ownership changes that alter the projected graph. Permission changes
must not leak formerly visible data or silently leave it in downstream caches. Test interruption
and resume against the equivalent complete snapshot.

**Covenant follow-up required:** consume the feed or wake-up signal, preserve bounded/coalesced
refresh, and keep manual/full refresh available.

## Compatibility fixture to include with the first change

Covenant at commit `b0456838ca8f9cdd687cabe85d669fc5de60ee88` uses
`POST /integration/graphql`, a Bearer integration key, and these query shapes:

```graphql
query CovenantUsage($page: Int!, $pageSize: Int!) {
  blueprints(page: $page, pageSize: $pageSize) {
    items { id identifier relations }
    page pageSize total
  }
}

query CovenantUsage($page: Int!, $pageSize: Int!, $blueprint: String!) {
  entities(page: $page, pageSize: $pageSize, blueprint: $blueprint) {
    items { id blueprint identifier title team relations updatedAt }
    page pageSize total
  }
}
```

Execute these as separate operations. Default mapped blueprints are `api`, `service`, `system`
and `_team`; service relation names are `provides_apis`, `consumes_apis` and `system`. These are
Toadie defaults, not universal Port identifiers. Blueprint definitions determine actual targets.

Pin current response semantics: decimal-string numeric IDs, ascending numeric-ID pagination,
epoch-millisecond `updatedAt`, effective `team` identifiers, and blueprint-scoped relation target
identifiers. GraphQL errors, including HTTP 200 with partial data, invalidate a Covenant refresh.
Preserve these semantics or coordinate an explicit consumer migration.

## Evidence and delivery expectations

Covenant references:

- [Integration behavior and limits](toadie-integration.md).
- [Client queries, validation and budgets](../../server/src/main/kotlin/toadie/ToadieGraphqlClient.kt).
- [Transport and response regression tests](../../server/src/test/kotlin/ToadieGraphqlClientTest.kt).

Toadie evidence (paths relative to `~/Sources/toadie`, lines at the inspected revision):

| Area | Implementation / existing checks |
| --- | --- |
| Public queries and numbered pages | `server/src/main/resources/graphql/schema.graphqls:7–52`; `server/src/main/kotlin/integration/Fetchers.kt:117–142` |
| Page reads and transaction boundaries | `server/src/main/kotlin/blueprints/BlueprintService.kt:151–160`; `server/src/main/kotlin/entities/EntityService.kt:377–426` |
| Client identities, key creation and revocation | `server/src/main/kotlin/integration/IntegrationClientService.kt:29–116`; `.claude/docs/integration-api.md`, “Clients and credentials” |
| Generic ontology fields | `server/src/main/resources/graphql/schema.graphqls:66–150` |
| Existing health report, distinct from a change feed | `server/src/main/resources/graphql/schema.graphqls:162–237` |
| Public schema compatibility checks | `server/src/test/kotlin/IntegrationSchemaContractTest.kt:14–22`; root fields and absence of mutation/subscription are explicitly pinned |
| Auth, response and budget regression coverage | `server/src/test/kotlin/IntegrationGraphQlTest.kt`; `server/src/test/kotlin/IntegrationLimitsTest.kt`; `server/src/test/kotlin/IntegrationClientSecurityTest.kt` |

The inspected SDL has no cross-request snapshot/revision or change-feed surface. Its generic JSON
properties can support new ontology templates; the absence of typed adoption fields does not
mean a dedicated Kotlin entity or GraphQL root is necessary. Test pointers above were inspected;
no runtime tests were executed for this documentation-only handoff.

For each selected Toadie work item, deliver its API/ontology design, migration and compatibility
notes, focused tests, and updated integration documentation. Report the exact changes needed in
Covenant separately. Implementing every optional item is not implied by this handoff.
