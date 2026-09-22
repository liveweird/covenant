# Toadie integration (usage 0.10.0; revisions 0.16.0; registries 1.0.0; adoption 1.1.0)

Toadie owns architecture and declared provider/consumer relationships in its Port ontology.
Covenant owns contract documents, versions, release lines and support policies. This connector
reads Toadie's existing `/integration/graphql` API; it does not use the Backstage catalog,
modify Toadie, synchronize accounts, or grant permissions from ownership. Since 1.0.0,
ADMIN may opt into one-way metadata synchronization for selected domains, systems and teams.
Local IDs, team rosters and contract permissions remain Covenant-owned. Read
[registry synchronization](toadie-registry-sync.md) before changing that boundary.

## Connection and mapping

ADMIN manages `/api/v1/toadie-connections`. Authenticated users read sanitized connection
metadata and cached API or dataset choices. The server base URL and browser URL are separate: a Compose
app can use `http://host.docker.internal:8081` while browser links use `http://localhost:8081`.
Production uses HTTPS. The server URL identifies the remote instance and is immutable; create
a separate connection to change instances. Numeric remote IDs are decimal strings, scoped to
that connection. Renaming an entity does not lose its mapping, and recreating an identifier
under a new ID does not inherit an old mapping.

Default mapping: `service`, `api`, `provides_apis`, `consumes_apis`, `system`. These are Toadie's
baseline blueprint/relation identifiers, not universal Port vocabulary. The connector checks
the actual blueprint definitions, derives the system blueprint from the mapped relation, and
resolves entity references byte-exact within each blueprint. Effective ownership comes from
Toadie's `team` field, including inherited teams, and `_team` supplies display names.

The default API blueprint covers OpenAPI and AsyncAPI. ODCS contracts can use the dataset
ontology shipped with Toadie 2.13.0. These are importable ontology definitions: upgrading
Toadie alone does not create them in an existing catalog. The configured blueprints and
relations must exist; custom identifiers remain supported.

### Configure ODCS dataset usage

1. Keep the existing API connection. Create a second, distinctly named connection such as
   **Local Toadie datasets**, using the same backend URL, browser URL and integration client
   key (or another valid key for that instance).
2. Open **Advanced mapping** and choose **Use dataset mapping**. The presets are explicit
   form actions; opening or editing a connection never replaces a saved custom mapping.
   Fields remain editable after applying either preset.
3. Save and wait for a successful refresh. The dataset preset requires these definitions:

| Mapping field | API preset | Dataset preset |
| --- | --- | --- |
| Service blueprint | `service` | `service` |
| API or dataset blueprint (`apiBlueprint`) | `api` | `dataset` |
| Provides or produces relation | `provides_apis` | `produces_datasets` |
| Consumes relation | `consumes_apis` | `consumes_datasets` |
| System relation | `system` | `system` |

4. Open an ODCS contract, choose **Edit Toadie links**, select the dataset connection, select
   the datasets represented by the document, and save. One ODCS document may link several
   datasets. A producer appears as **Provider**; a service may have both roles and is shown
   once even when it references several selected datasets.

Each connection has one target blueprint and one provides/consumes relation pair. API and
dataset mappings therefore use **two connections**, even to one instance; their snapshots,
refresh status and links are independent. Each contract links entities from **one connection**.
Changing an existing connection's mapping invalidates its cache, so create the second
connection rather than repurposing the API connection.

Registry synchronization is optional and separate. For the same upstream registries, reuse
one chosen registry-sync connection and its bindings; do not import the same registries again
through the new dataset connection. Mapping presets preserve the registry settings already
entered in the form.

Link datasets explicitly: an ODCS schema name, database name, `stored_in` relation or service
dependency on a database does not establish dataset consumption. No dataset is inferred or
created automatically. Optional `api_adoption` and `dataset_adoption` entities can be read through a separate opt-in
[adoption mapping](toadie-adoption.md). Runtime adoption remains unknown. No Toadie code changes are
needed for this setup.

The existing API paths and field names, including `/apis`, `apiBlueprint`, `apiEntityIds`
and the usage row's API-ID lists, also represent the configured dataset entities. They remain
unchanged for API-client compatibility.

## Linking and reading usage

`GET/PUT /api/v1/contracts/{id}/toadie-links` reads or replaces a contract's mapping. One contract
can link up to 100 API or dataset entities from one connection, allowing one AsyncAPI document to cover
multiple topics. PUT requires the current contract writer, both before body decoding and in
the committing transaction. A null connection and empty IDs clears the mapping. Link changes
use the shared contract activity path; automatic observations do not generate product history.

`GET /api/v1/contracts/{id}/toadie-usage` returns a paged table of services with provider/consumer
roles, linked API or dataset IDs, systems, teams, and safe Toadie links. It deduplicates a service appearing
through several linked APIs or datasets and can show both roles. Legacy version and release-line fields remain
explicitly unknown: architecture consumption is not a runtime adoption measurement. The separate
paged `/toadie-adoptions` endpoint preserves source declarations, including parallel environments
and missing architecture consumption edges; see [declared adoption](toadie-adoption.md).

Missing mapped APIs or datasets remain visible; disconnected connections do not erase their labels. A
current empty result means **no declared usage observed**, never proof that a contract is unused.
Port ownership/lifecycle and Covenant ownership/lifecycle have independent meanings.

## Refresh and failure behavior

Enabled connections refresh periodically. ADMIN can request connection refresh; a contract
writer can request its usage refresh. Both return 202 for accepted work and share coalescing,
cooldown and bounded worker capacity. Refresh runs outside database transactions. Publication
checks the configuration revision and refresh claim, so an old job cannot overwrite a changed
configuration or publish into a deleted connection.

Only a complete successful scan replaces the cached observation. GraphQL can return HTTP 200
with errors: those responses, failed later pages, malformed mapped relations, changing totals,
duplicate/out-of-order IDs, and budget exhaustion are failures. The last successful observation
is retained and marked stale; never convert a failure into zero consumers. Ontology mapping changes invalidate the cache; key rotation and display-only edits retain
the previous observation and schedule another refresh when enabled. Disable/delete, never synced, stale, missing and disconnected states
are distinct from a current successful empty observation.

Refresh requires **Toadie 2.12.0 or newer**, whose blueprint and entity pages carry a persistent
ontology `revision`. Every page in a full scan must have the same canonical nonnegative decimal
revision, including empty entity pages and scans of different blueprints. A final revision-only
probe also confirms the graph has not changed during response materialization. A changed revision
discards the attempt and restarts from blueprint discovery once. Both attempts share the whole
refresh deadline and request, byte and row budgets. Persistent churn exhausts the bounded retry;
the previous complete cache remains visible with a stale/error status. Missing/malformed revisions
and GraphQL errors fail the refresh; there is no silent fallback to unverified older pagination.

Scheduled refreshes first request `blueprints(page: 1, pageSize: 1) { revision }` when a prior
verified revision is available. If unchanged, they reconfirm freshness without fetching entities
or rewriting snapshot rows. `lastSuccessAt` means the latest successful full scan **or**
same-revision verification. Failed probes never advance it. Manual refresh always performs a
full scan and clears the saved revision while retaining the last observation. If a scheduled
revision-aware job is already running, manual refresh promotes it to a new full-scan claim;
the old job cannot publish, and repeated manual requests coalesce into the full-scan job.
Each accepted claim has its own fixed budgets. Mapping changes invalidate the cache and saved revision; explicit API-key replacement
clears the saved revision while preserving the previous observation. Display-only edits retain
both. Existing configuration-revision and refresh-claim guards protect both publication paths.

This is change detection, not a server-held historical snapshot or evidence of runtime adoption.
The source can change after the final read. There is no webhook/subscription/delta protocol and
no automatic retirement gate. Optional adoption declarations share this same scan and revision
boundary when enabled; they are source claims, not observed runtime adoption.

## Credentials and transport

Toadie must have its integration API enabled (`INTEGRATION_ENABLED=true`; its local Compose
demo already does) and an ADMIN-created integration client. Its API key is separate from user
JWTs and grants read access to the whole Port ontology. Store it only in Covenant's encrypted
connection field; absent/null on update retains it, and responses expose `hasApiKey` only.
The service participates in the shared encryption-key rotation/bootstrap registry.

The trust boundary is the ADMIN-curated endpoint, analogous to Environments: private service
addresses are intentional. URL credentials, query strings, fragments and link-local literals
are rejected; production refuses plain HTTP. Fixed GraphQL queries use variables, never string
interpolation of supplied identifiers. Redirects are disabled so a key cannot follow them.
Requests have a whole-refresh deadline, response/cumulative size caps and page/row/request caps.
Only selected metadata, team identifiers and mapped relations enter the snapshot; arbitrary
properties, creator details, upstream error text and credentials do not.

## Deployment and verification

V17 is additive; V22 adds nullable `toadie_connections.remote_revision` (BIGINT) cache metadata.
Existing connections begin with no verified revision and perform a full scan on their next
refresh. Connections are soft-deleted; snapshot rows are replaceable cache entries and contract
links are hard-deleted membership rows, with explicit changes retained in contract history.
Existing catalogs and samples need no changes. No Toadie code migration is required. Connection credentials and contract mappings are persistent configuration; don't
overwrite them in tests. Browser verification owns a separate connection, contract and fixture
GraphQL server, then removes only its own records through the APIs.

The upstream revision has no database-epoch identifier. After restoring or replacing Toadie's
database at the same URL, manually refresh each connection to rebuild its observation even if
the counter happens to match. Before rolling Covenant back to a binary that ignores V22,
invalidate the derived remote-revision metadata; otherwise invalidate it before returning to
revision-aware code. Older binaries can replace cache rows without updating that metadata.
With Covenant stopped, invalidate only the derived column using
`UPDATE toadie_connections SET remote_revision = NULL;`. Keep connection credentials, contract
links and snapshot rows intact.

Application rollback must account for the new `TOADIE_LINKS_UPDATED` history/notification enum:
after link changes exist, use a compatible binary or restore the pre-upgrade database snapshot.
An additive database migration alone does not make older binaries understand new event types.

Test boundaries include real HTTP pagination/error/cancellation handling; encrypted persistence
and rotation; role/writer guards; revision races; identity-preserving rename and missing IDs;
schema conformance; read-only and editable SPA states; translations; and browser accessibility.

## Migration report exports (0.15.0)

Release-line migration reports reuse the complete cached usage projection under a read-only
local snapshot transaction, with all linked APIs or datasets and provider/consumer services independent of
UI paging. They retain missing mappings and stale/disabled observation warnings, and do not
refresh the connection. The selected Covenant major is planning context only. The separate declaration collection
includes all linked targets and environments, without inferring a match to that major; runtime
version and major adoption remain unknown. Generation time never replaces `lastSuccessAt` as the
observation timestamp (which can be a same-revision reconfirmation). See `release-lines.md` for
the report API and sharing behavior.
