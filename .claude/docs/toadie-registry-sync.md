# Registry synchronization from Toadie (1.0.0)

Covenant can use Toadie's **Port ontology** as the source of selected domain, system and team
metadata. This is an opt-in extension of the existing read-only integration. It makes no
Toadie writes and does not read the Backstage catalog. Existing local registries and demo
records remain local until an ADMIN explicitly links them.

## Ownership and projection

Covenant retains its own registry IDs, contract assignments, Environments, team rosters,
accounts, contract ownership and lifecycle decisions. Importing a Port team creates no
memberships or permissions. Linking an existing team preserves its roster. Port's entity
`team` field describes architectural ownership, not Covenant authorization.

A linked record's name follows the upstream entity title. Its description follows Toadie
only when an administrator explicitly maps a stored string property for that kind; otherwise
an existing local description stays local and a new record starts without a description.
Once a configured description is successfully synchronized, it stays source-owned until
the record is detached, including while the mapping is disabled. Enabling a description
mapping later transfers that field to the source on a successful reconciliation.
The blueprint's own description is documentation of an entity type, not entity metadata.
Unmapped properties, computed properties, `_user` entities and credentials are not retained.

The registry mapping uses a configured domain blueprint, the system blueprint already derived
by the usage mapping, and protected `_team`. Port blueprint and relation names are deployment
configuration, not universal Port vocabulary. Configured description properties must exist
in the blueprint's `schema.properties` and have type `string`.

Covenant's domain registry is flat. Administrators explicitly acknowledge flattening before
enabling registry sync; a nested remote domain is a separate flat local domain, with its
remote parent visible in the selection flow. Teams also remain flat; selecting an
organizational unit does not import its hierarchy or members.

A linked system follows its remote domain relation to a linked Covenant domain from the
same connection. Import or link domains before systems. If a remote domain exists but is
unmapped, report a conflict and preserve the current placement. If the source system has no
domain, an explicit per-system local fallback domain is required. Never guess a domain,
create one automatically or assign a system to an unrelated name match.

## Administrator flow

1. Configure the optional registry mapping on a Toadie connection and refresh its cache.
   For an unconfigured connection, **Sync registry metadata** explains the required setup;
   **Configure registry sync** opens the registry mapping section without enabling it automatically.
   Failed candidate reads stop loading and expose a retry action.
2. Browse the paged domain, system or team candidates. Select at most 50 records of one kind.
3. Choose import for a new record, or explicitly select the existing local record to link.
   Matching names are not identity. The stored binding uses connection-scoped numeric IDs.
4. Preview the proposed before/after metadata, placement and conflicts.
   Each connection supports at most 5000 registry bindings; exceeding that limit produces
   a `SOURCE_LIMIT_EXCEEDED` preview conflict.
5. Apply the preview. The server checks its opaque plan token against current configuration,
   cached source data and relevant local records under transaction locks. A changed plan
   returns `409`; refresh the preview before applying again. The batch is atomic.

Only ADMIN may configure, preview, apply or detach registry sources. Source metadata and
status are visible on registries to authenticated readers. A linked record's source-owned
fields cannot be changed through the ordinary edit API. Team roster management remains
available. Detaching preserves the record's ID and current metadata and returns it to local
management; ordinary delete protections still apply afterward.

## Automatic refresh and conflicts

The existing revision-aware reader includes the extra domain pages and selected registry
properties in the same bounded, revision-consistent scan. The existing whole-read time,
request, byte and decoded-row limits cover the combined graph. Registry mapping changes
invalidate the saved revision, so an unchanged-revision shortcut cannot skip a new projection.

A successful refresh reconciles **already-linked** records; it does not import newly
discovered entities. Valid metadata changes and the new snapshot publish in one transaction.
Expected per-record conflicts preserve that record's last local metadata and appear in its
source status. Other valid records can still update. An unchanged-revision verification also
reconciles bindings against the cached snapshot, allowing a resolved local conflict to recover.

Oversized names/descriptions, invalid descriptions, unavailable domain mappings and local
uniqueness conflicts are visible conflicts, never silent truncation or name-based merging.
A non-string description or one longer than 2000 characters produces an `INVALID_DESCRIPTION`
projection marker without retaining the value; the team's stricter local 500-character limit
is checked during reconciliation. Failed upstream scans preserve the entire previous snapshot.

Source availability and cache freshness are separate: a record may be missing or conflicting
in an observation that later becomes stale. Disabled or deleted connections never delete
local records. Upstream disappearance never cascades into contracts, Environments or team
memberships. Recreating an upstream identifier under a new numeric ID does not inherit a link.

## Persistence and concurrency

V23 adds opt-in registry configuration, selected snapshot metadata and per-kind source-link
tables with real foreign keys to local domains, systems and teams. Bindings are relationship
rows and detach removes the binding; the business records retain the ordinary soft-delete
convention. No existing records are automatically linked or rewritten by the migration.

A transaction-scoped PostgreSQL advisory lock serializes the small registries' identity and
placement writes, including sync and detach. Ordinary registry mutations take this gate before
record/parent locks; roster mutations do not need it. Sync takes the connection lock before
the registry gate. Ordinary registry edits never acquire a connection lock. Existing active
parent checks and delete protections remain in force. This ordering makes uniqueness and
placement preflight stable without a general-purpose synchronization engine.

Read `.claude/docs/toadie-integration.md` for credentials, transport, revision semantics,
source database restore handling and cache failure behavior. This feature does not implement
identity federation, membership provisioning, contract adoption tracking or CI/CD gates.
