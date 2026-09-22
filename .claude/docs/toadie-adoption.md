# Declared adoption from Toadie (1.1.0)

Toadie owns declarations about consumption in its Port ontology. Covenant reads them alongside
architecture usage and includes them in lifecycle impact views and migration reports. Neither
application observes runtime traffic through this feature. Declarations never grant permissions,
change lifecycle, or establish that retirement is safe. No Toadie code change or CI/CD integration
is required; the optional ontology blueprints must have been imported into the source catalog.

## Opt-in configuration

Edit an ADMIN-managed Toadie connection and enable **Read declared adoption**. Choose the
separate API or dataset adoption mapping preset, then save. Existing connections and the ordinary
API/dataset usage presets leave adoption disabled. Opening an editor never replaces custom
mapping fields. API and dataset usage still use separate connections to the same instance.

| Field | API adoption preset | Dataset adoption preset |
| --- | --- | --- |
| Blueprint | `api_adoption` | `dataset_adoption` |
| Kind | `API_MAJOR_LINE` | `DATASET_CONTRACT_VERSION` |
| Consumer relation | `consumer` | `consumer` |
| Target relation | `api` | `dataset` |
| Environment relation | `environment` | `environment` |
| Value property | `major_line` | `contract_version` |
| Status property | `status` | `status` |
| Declared-by property | `declared_by` | `declared_by` |
| Verified-at property | `verified_at` | `verified_at` |
| Notes property | `notes` | `notes` |

Custom Port identifiers remain editable. Optional mapping fields can be omitted. The consumer
relation must target the configured service blueprint and the target relation the configured
API/dataset blueprint; both are singleton relations. The optional singleton environment relation
identifies a remote environment blueprint. These are Toadie environments, never Covenant's
curated connection targets. `declaredBy` is an upstream identifier, not a Covenant account.
Adoption and environment blueprints must be distinct from each other and from the mapped
service, API/dataset, system, team and registry-domain roles. Mapped relations and properties
must have distinct identifiers; ambiguous blueprint definitions fail the refresh.

`adoptionMapping: null` disables reading. Connection PUT uses the existing complete replacement
semantics: clients preserving adoption settings must send their current mapping. Changing the
mapping invalidates the cached graph and remote revision and schedules a new scan when enabled.

## Reading declarations

`GET /api/v1/contracts/{contractId}/toadie-adoptions` is readable by authenticated users and uses
the shared paging envelope, `id`/`title` sorting and optional `q` search. It selects declarations
for this contract's explicitly linked targets from its connection's complete cached snapshot.
It never contacts Toadie during a read or derives links from names, URLs or database dependencies.

Each record preserves its identity, consumer, target, optional environment, declaration kind,
raw value/status, declared-by identifier, verification time and notes. API major-line strings
such as `v2` and dataset version strings remain declarations exactly as supplied; Covenant does
not normalize, validate them as its own versions, or assume they exist in the contract catalog.
Source RFC 3339 verification timestamps with four-digit years become nullable epoch milliseconds in the API. They remain
separate from the cache's latest successful scan/reconfirmation time and report generation time.

- Missing value means **unknown/undeclared**, not the recommended or latest version.
- An empty mapped environment relation means **all environments**, following Toadie's ontology
  definition. With no environment relation mapping, scope is **unknown**, not global.
  `environmentScope` distinguishes `ALL`, `SPECIFIC` and `UNKNOWN`.
- Parallel and conflicting records remain separate. No record wins by recency, status or scope.
- `matchesConsumption: false` identifies a declaration whose consumer lacks the corresponding
  architecture consumption relation. Such records remain visible with a discrepancy notice.
- Status is source metadata. Known `current`, `migrating` and `retiring` values have translated
  labels; it is independent of Covenant lifecycle. It never filters a declaration out.

The existing architecture table, provider/consumer counts and lifecycle overview counts retain
their original meanings. Legacy scalar `version`/`releaseLine` and report `versionAdoption`
remain unknown for runtime adoption; the separate declaration collection contains source claims.
The lifecycle impact view displays the same paged declarations without inferring which ones
belong to its selected Covenant major. Migration reports include **all** linked declarations,
regardless of current UI filters or page, with source provenance and freshness warnings.

## Availability, consistency and failure

Declaration availability is separate from connection cache freshness:

| Availability | Meaning |
| --- | --- |
| `NOT_CONFIGURED` | Reading adoption is disabled for this connection. |
| `NOT_SCANNED` | No usable adoption scan is available. |
| `BLUEPRINT_MISSING` | A complete scan found no configured adoption blueprint. |
| `AVAILABLE` | The configured blueprint was read successfully; records may be empty. |

An absent optional blueprint does not break architecture usage. A present malformed mapped
blueprint, property, timestamp or reference fails the entire refresh and retains the previous
complete snapshot with stale/error status. Never turn a failed scan into zero declarations.
Only an available, current, complete observation supports “no declarations found”; it still
cannot establish absence of runtime consumers.

Adoption and environment reads use the existing connection refresh loop, all shared request,
row, byte and deadline budgets, revision validation and one bounded restart. No second worker
or independent budget is introduced. Same-revision scheduled checks can reconfirm this snapshot;
manual refresh fully scans it. Existing configuration revisions and refresh claims guard
publication. Only allowlisted metadata is persisted; credentials and arbitrary properties are
never exposed. Notes are displayed as text and escaped in Markdown exports.

Reports read architecture and adoption in the existing read-only REPEATABLE_READ transaction.
The combined output is bounded by the existing 8 MiB export limit, with no silent truncation.

## Migration and rollback

V24 adds optional adoption configuration and replaceable cache metadata. Older connections
start disabled. Never edit earlier migrations or erase credentials, links or local memberships.
Before returning to this reader after running an older binary, stop Covenant and invalidate
both the derived adoption availability and remote revision: an older binary can replace snapshot
rows without updating newer metadata. Re-enable a full refresh before trusting declarations.
With Covenant stopped, reset only the derived fields before restarting the newer binary:

```sql
UPDATE toadie_connections
SET snapshot_adoption_availability = 'NOT_SCANNED',
    snapshot_adoption_environment_blueprint = NULL,
    remote_revision = NULL;
```

This leaves persistent adoption configuration intact. The next enabled refresh fully rebuilds
the cache; old snapshot rows must not be interpreted as verified adoption until then.

Browser tests use a test-owned GraphQL fixture and isolated Covenant records. They never write
to live Toadie or alter existing connections. Follow the ordinary API cleanup protocol.
