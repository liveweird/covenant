# Toadie contract usage (0.10.0)

Toadie owns architecture and declared provider/consumer relationships in its Port ontology.
Covenant owns contract documents, versions, release lines and support policies. This connector
reads Toadie's existing `/integration/graphql` API; it does not use the Backstage catalog,
modify Toadie, copy service registries, synchronize accounts, or grant permissions from ownership.

## Connection and mapping

ADMIN manages `/api/v1/toadie-connections`. Authenticated users read sanitized connection
metadata and cached API choices. The server base URL and browser URL are separate: a Compose
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

The default API blueprint covers OpenAPI and AsyncAPI. An ODCS dataset requires an explicit
data-contract/dataset ontology mapping; a service's dependency on a database is not evidence
that it consumes a particular dataset contract. The connector does not infer that relationship.

## Linking and reading usage

`GET/PUT /api/v1/contracts/{id}/toadie-links` reads or replaces a contract's mapping. One contract
can link up to 100 API entities from one connection, allowing one AsyncAPI document to cover
multiple topics. PUT requires the current contract writer, both before body decoding and in
the committing transaction. A null connection and empty IDs clears the mapping. Link changes
use the shared contract activity path; automatic observations do not generate product history.

`GET /api/v1/contracts/{id}/toadie-usage` returns a paged table of services with provider/consumer
roles, linked API IDs, systems, teams, and safe Toadie links. It deduplicates a service appearing
through several linked APIs and can show both roles. Version and release-line fields are
explicitly unknown: architecture consumption is not a deployment or adoption measurement.

Missing mapped APIs remain visible; disconnected connections do not erase their labels. A
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

Toadie's numbered pagination has no cross-request snapshot token. Even a fully validated scan
is an observation over an interval, not an atomic remote graph snapshot. It must not be used as
an automatic retirement gate. There is no webhook/subscription/delta protocol in this first
connector. A future precise adoption model can add a Port usage entity carrying the consumer,
API and version or release line; no such precision is inferred today.

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

V17 is additive. Connections are soft-deleted; snapshot rows are replaceable cache entries and
contract links are hard-deleted membership rows, with explicit changes retained in contract history. Existing catalogs and samples need no changes. No Toadie code migration is
required. Connection credentials and contract mappings are persistent configuration; don't
overwrite them in tests. Browser verification owns a separate connection, contract and fixture
GraphQL server, then removes only its own records through the APIs.

Application rollback must account for the new `TOADIE_LINKS_UPDATED` history/notification enum:
after link changes exist, use a compatible binary or restore the pre-upgrade database snapshot.
An additive database migration alone does not make older binaries understand new event types.

Test boundaries include real HTTP pagination/error/cancellation handling; encrypted persistence
and rotation; role/writer guards; revision races; identity-preserving rename and missing IDs;
schema conformance; read-only and editable SPA states; translations; and browser accessibility.

## Migration report exports (0.15.0)

Release-line migration reports reuse the complete cached usage projection under a read-only
local snapshot transaction, with all linked APIs and provider/consumer services independent of
UI paging. They retain missing mappings and stale/disabled observation warnings, and do not
refresh the connection. The selected Covenant major is planning context only: exact version
and major adoption remain unknown. Generation time never replaces `lastSuccessAt` as the
observation timestamp. See `release-lines.md` for the report API and sharing behavior.
