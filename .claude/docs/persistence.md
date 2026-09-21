### Persistence

**Version reviews (V19).** `contract_versions.content_revision` is a monotonic byte-change
counter exposed on version detail. Review rounds capture it and the content hash, and immutable
entries hold discussion/decisions. Rounds and entries are retained history/detail records
(no edit/delete API), an explicit exception to business-entity soft-delete columns; every read
checks their active parent contract/version. Review services may read contract/version/user
tables in their transactions. Content saves and lifecycle transitions close open reviews in
the same parent-locked transaction; entry writes lock that parent before rechecking their round.
See [version reviews](version-reviews.md) for closure, stale-client and ABA semantics.

PostgreSQL is the only database. Connection settings come from the `postgres:` block in `application.yaml` (env-overridable via `POSTGRES_JDBC_URL`, `POSTGRES_R2DBC_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`); defaults match the `docker compose up postgres` service (host port **5434** — Lettuce and Toadie may occupy 5432/5433 on the same machine; in-network consumers use `postgres:5432`). There is one persistence stack:

- **Flyway** (`infra/db/Flyway.kt`) — runs schema migrations from `server/src/main/resources/db/migration/` at startup via the Java API, opening a short-lived JDBC connection. Migrations are the single source of truth for schema; do not call `SchemaUtils.create` anywhere. **An applied migration's bytes are immutable — comments included**: Flyway validates stored checksums at startup, so any edit to an existing `V*.sql` makes every long-lived database refuse to boot (a failure no fresh-container CI run can see). `MigrationChecksumTest` pins every file's checksum; a new migration adds one manifest line, and a red pin means REVERT the edit (clarifications go into this doc), never update the pinned value.
- **Exposed + R2DBC** (`infra/db/Database.kt` + the feature services) — runtime DB access. `Database.kt` connects the `R2dbcDatabase` and is the composition root: it constructs the services and publishes them into `Application.attributes` (`UserServiceKey`, `TeamServiceKey`, `DomainServiceKey`, `SystemServiceKey`, `EnvironmentServiceKey` (constructed with the `FieldCipher` that `configureCrypto` published — hence Crypto before Database in the module order), the contract services, `ContractImporterKey` and `ContractUrlFetcherKey` (the import pipeline and the SSRF-guarded fetcher — stateless, built here since the checkup so every collaborator has one home; a test pre-puts the fetcher key to aim a fetch at a fixture, and the route reads it per request), `NotificationServiceKey`, `ContractActivityKey`, `TokenBlocklistServiceKey` today; `SystemService` takes the `DomainService` it checks domain ids against); each service itself lives next to the feature it serves (`users/UserService.kt`, `teams/TeamService.kt`, `domains/DomainService.kt`, `systems/SystemService.kt`, `auth/TokenBlocklistService.kt`). The Exposed table `object`s (e.g. `UserService.Users`, nested inside their service) are used for queries only, not DDL. Ids are `UIntIdTable` — unsigned end-to-end (the spec declares `minimum: 0`, and `ErrorHandling.kt` 400s negative path segments before kotlinx's `UInt` decoding can silently wrap them); one wrinkle inherited from Toadie: V1 creates `users.id` as `BIGSERIAL` (64-bit in SQL, 32-bit everywhere above it) while every later table uses `SERIAL`/`INTEGER` — harmless at this scale, documented so nobody "fixes" one to match the other without a migration (an `INTEGER` FK column may reference the `BIGINT` id; Postgres compares them fine).

The `org.postgresql:postgresql` JDBC driver is on the classpath solely for Flyway; runtime queries go through R2DBC.

**Cross-feature table reads (the service-layer rule, inherited from Lettuce).** A feature service MAY query another feature's Exposed table objects directly when the read must run **inside its own transaction** (SQL joins, atomic snapshots) — the transaction boundary must be explicit rather than relying on an unrelated service to preserve it. Exposed reuses an enclosing transaction for nested `suspendTransaction` calls on the same database; the importer deliberately uses that behavior to compose one atomic item. Route handlers never touch tables (services only). The reads in place: `TeamService` joins `UserService.Users` for the roster's display fields and the active-member counts, and checks member ids against active users inside the create/add transaction; `TeamService.activeTeamIdsOf` is written to be called from the contract write's transaction (the writer guard); `DomainService` counts active rows of `SystemService.Systems` per domain (the list caption and the holds-systems delete rule) and `SystemService` joins `DomainService.Domains` for the domain name and checks the domain id inside its write transaction. The contract catalog adds the creator/owner display-name joins and the Domain → System → Contract tree assembled from three reads in one transaction; `ContractService.facts` reads `ContractSubscriptionService.ContractSubscriptions` for the per-row follower counts and the caller's own follows (`subscribed`/`subscriberCount` on every contract response); `ContractSubscriptionService.subscribe` checks the contract row inside its insert transaction. `EnvironmentService` joins `SystemService.Systems` for the system name and checks the system id inside its write transaction; **`SystemService.delete` soft-deletes the system's rows in `EnvironmentService.Environments` in the same transaction** — a sanctioned cross-feature WRITE (an environment is configuration OF its system); the other two writes are intra-package — `ContractVersionService` recomputes `ContractService.Contracts.latest_version_id` inside every version write and `ContractService.delete` soft-deletes the contract's rows in `ContractVersionService.ContractVersions` (the pair frees its identities as one unit). `SystemService` counts active rows of `ContractService.Contracts` per system (`contractCount` on every response and the holds-contracts 409 on delete) and `TeamService.delete` counts the contracts the team still owns (the owns-contracts 409) — the hierarchy's delete rules run one level down, inside the deleting transaction. `infra/db/EventLog.kt` joins `UserService.Users` to resolve the acting user's display name on every history page. `ContractErrorService` (the Errors report) joins the shared `contracts/ContractJoins.kt` spine — `ContractService.Contracts` ⋈ `SystemService.Systems` ⋈ `DomainService.Domains` ⋈ the two owner OUTER joins — with `ContractVersionService.ContractVersions` (every ACTIVE version, not `ContractService`'s "latest" alias), a pure read with no write of its own. List each here as it lands — the list IS the permission.

Current migrations are `V1`–`V22`. The foundation below covers `V1`–`V15`; `V16` adds major
release lines (see `release-lines.md`), `V17` adds Toadie usage (see the section below), `V18`
adds lifecycle planning/reminders, `V19` adds version reviews (see `version-reviews.md`), `V20`
adds user credential revisions, and `V21` adds the immutable
`covenant_semver_prerelease_key(TEXT) → TEXT[]` helper used for database SemVer ordering. V21
encodes numeric prerelease identifiers at the stored maximum width of 100 digits, leaves existing
data untouched, and callers apply explicit `C` collation to its text-array result. V22 adds nullable
remote ontology revision metadata to the Toadie cache (see below).
The foundation combines Toadie's auth/users migrations with the flat teams, registries and
contract catalog:

- `V1__init` — the `users` table: `name` (≤50), `email` (≤254), `password_hash`, `role` with `CHECK ("role" IN ('ADMIN', 'USER'))` (single-column role storage; the wire shape stays a `roles` set, see `.claude/docs/authorization.md`), `password_changed_at` (epoch millis, 0 = never — retained as a timestamp; V20 credential revisions govern refresh acceptance), `language VARCHAR(10) NOT NULL DEFAULT 'en'` (the per-user language, Lettuce's V61 / Toadie's V18 folded in — no CHECK, `SUPPORTED_LANGUAGES` in `users/Languages.kt` is the whitelist; drives the UI at sign-in and every server-composed email; set at create, changed only via `PUT /users/{id}/language`), `marked_as_deleted`; plus the partial unique index `uq_users_email_active` over active rows.
- `V2__create_revoked_tokens` — the JWT blocklist for `/logout`: `jti` PK + `expires_at`, with an index on `expires_at` (the revoke path prunes expired rows opportunistically, so the table stays tiny).
- `V3__seed_admin` — the bootstrap administrator `admin@covenant.local` / `changeme`, idempotent via `ON CONFLICT DO NOTHING`; production neutralizes it at startup (see "Default admin" in `.claude/docs/security.md`).
- `V4__enable_unaccent_extension` — Lettuce's unaccent migration, backing every `containsNormalized` substring filter (see `infra/db/Sql.kt`).
- `V5__user_disabled_features` — Lettuce's per-user feature flags (the DISABLED set — no row = enabled, so the empty table needs no backfill): `(user_id, feature)` PK, `ON DELETE CASCADE`, no CHECK on feature (the Kotlin `Feature` enum is the whitelist — the V1 role-CHECK is the deliberate exception, not the rule), plus the feature index behind the users-list `feature`/`featureEnabled` filter pair; and, in the same file, the MFA seed (Toadie's V13): MFA joins the flags with an INVERTED default (opt-in) — every pre-existing user gets the `MFA` disabled row (`ON CONFLICT DO NOTHING`); `UserService.create` inserts the same row for every later user.
- `V6__create_teams` — flat teams (Lettuce's V2 minus `manager_id` — no management chain by design): `teams` (`name` ≤100, `description` ≤500 nullable, epoch-millis `created_at`/`updated_at`, soft-delete) with the partial unique index `uq_teams_name_active` over `LOWER(name)` active rows (a deleted team frees its name, no case twins), and `team_members` — a **hard-delete join** (`team_id` CASCADE, `user_id` CASCADE, composite PK, an index on `user_id` for the "my teams" filter and the writer guard): no history worth keeping, wholesale add/remove per row; a soft-deleted user keeps the row (reads join `users` and flag it `deleted`), a soft-deleted team keeps its roster for the record. `SERIAL`/`INTEGER` ids with a `BIGINT` FK to `users` (the V1 wrinkle).
- `V7__create_domains` — the top of the Domain → System → Contract hierarchy: `domains` (`name` ≤100, `description` ≤2000 nullable, timestamps, soft-delete) with `uq_domains_name_active` over `LOWER(name)` active rows.
- `V8__create_systems` — `systems` (`domain_id` FK `ON DELETE RESTRICT` — a domain is only ever soft-deleted, and the service refuses to delete one that still holds an active system, `name` ≤100, `description` ≤2000, timestamps, soft-delete) with `uq_systems_domain_name_active` over `(domain_id, LOWER(name))` active rows — a system name is unique WITHIN its domain — plus `idx_systems_domain_id` for the domain filter and the per-domain counts.
- `V9__create_contracts` — `contracts` (`system_id` FK RESTRICT, `"type"` with `CHECK IN ('OPENAPI','ASYNCAPI','ODCS')` — the value drives the validator dispatch, `name` ≤100, `description` ≤2000, `owner_team_id`/`owner_user_id` both FK RESTRICT with `ck_contracts_owner_xor` — exactly one owner side, `created_by`, timestamps, soft-delete) with `uq_contracts_system_name_active` over `(system_id, LOWER(name))` active rows — a contract name is unique WITHIN its system — plus indexes on `system_id`, `owner_team_id`, `owner_user_id` for the list filters and the holds-contracts checks (`SystemService.delete` / `TeamService.delete` → `409`, and the per-system `contractCount`).
- `V10__create_contract_versions` — `contract_versions` (`contract_id` FK RESTRICT; `version` ≤64 verbatim plus the parsed `semver_major/minor/patch/prerelease` columns for SQL ordering — the Kotlin `SemVer` comparator owns precedence; `lifecycle` with `CHECK IN ('DRAFT','PROPOSED','ACTIVE','DEPRECATED','RETIRED')` default DRAFT; `format` `CHECK IN ('yaml','json')`; `content TEXT` byte-exact and never rewritten + `content_sha256`; the extracted `doc_title`/`doc_description`/`spec_version`; the check snapshot — `findings TEXT` (a JSON `Finding[]`, wholesale-replaced with the content, capped at 500 with a `FINDINGS_TRUNCATED` marker), the denormalized `check_errors/warnings/infos` counts behind the list badges and `hasErrors`, `check_complete` (false after a sidecar outage until a recheck) and `checked_at`; `created_by`, timestamps, soft-delete) with `uq_contract_versions_version_active` over `(contract_id, version)` active rows, plus `ALTER TABLE contracts ADD latest_version_id` (FK `ON DELETE SET NULL`) — the highest active version's id, recomputed inside every version write's transaction.
- `V11__create_contract_events` — the `EventLogTable` clone for the contract's product history: `contract_id` FK CASCADE, `user_id` FK RESTRICT, `created_at`, `event_type` ≤40, `params TEXT` (small string maps — versions, `from`/`to`, owners as `TEAM:<id>`/`USER:<id>`, the linked `sourceUrl`; never the document text), indexed by `(contract_id, created_at)`.
- `V12__contract_version_source` — Toadie's V21 envelope on `contract_versions` (the unit of text): `source_url VARCHAR(2048)` (NULL = no reference; static guards only at write time — absolute https, no credentials — the public-host check runs at fetch time), `last_synced_at BIGINT NOT NULL DEFAULT 0` (0 = never; a sync stamps it EQUAL to `updated_at`, so `updated_at > last_synced_at` reads "edited in Covenant since the sync"; a changed/cleared reference resets it to 0) and `synced_content TEXT` (the text as pulled at the last sync — the baseline the SPA attributes a difference with; NULL = never). A version created from a fetched URL (`VersionCreateRequest.sourceUrl`, the import page) is stamped synced at birth; a reference-only edit never bumps `updated_at`.
- `V13__create_contract_subscriptions` — who FOLLOWS a contract (milestone 3): `(contract_id FK contracts RESTRICT, user_id FK users CASCADE, created_at)` with the composite PK and `idx_contract_subscriptions_user_id`. A hard-delete membership join like `team_members`: follow is an idempotent `insertIgnore`, unfollow a delete; the follower set is the recipient set of the contract's notifications.
- `V14__create_notifications` — Lettuce's V13 + V17 consolidated: `notifications(id SERIAL, recipient_id FK users RESTRICT, created_at, notification_type ≤60 — the Kotlin `NotificationType` enum is the whitelist, params TEXT — a JSON string map (the contract's name, the actor's name, version numbers, lifecycle names; never document text), link TEXT — a language-independent SPA path, was_seen, marked_as_deleted)` + indexes on `recipient_id`, `marked_as_deleted` and the partial `idx_notifications_unseen (recipient_id) WHERE NOT was_seen AND NOT marked_as_deleted` behind the bell's badge (the `total` of a pageSize-1 unseen query). Soft-deleted; no create API — minted by `contracts/ContractActivity.kt`.
- `V15__create_environments` — the try-it targets (milestone 3c): `environments(id SERIAL, system_id FK systems RESTRICT, name ≤50, description ≤2000, http_base_url ≤2048, kafka_bootstrap_servers ≤1024, kafka_security_protocol CHECK IN (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL), kafka_sasl_mechanism CHECK IN (PLAIN, SCRAM_SHA_256, SCRAM_SHA_512), kafka_username ≤200, kafka_password TEXT — ENCRYPTED, pg_jdbc_url ≤2048, pg_username ≤200, pg_password TEXT — ENCRYPTED, timestamps, soft-delete)` with `uq_environments_system_name_active` over `(system_id, LOWER(name))` active rows and `idx_environments_system_id`. The two password columns hold `FieldCipher` envelopes (`infra/crypto/`; `EnvironmentService` implements `EncryptedAtRest` and is registered in the bootstrap backfill list) — never filter or sort on them.

The contract tables follow Toadie's dialect (`SERIAL`/`INTEGER` ids, epoch-millis `BIGINT` timestamps, `marked_as_deleted` + partial unique indexes over active rows, `ON DELETE RESTRICT` on ownership/creator FKs) and its idioms: a CHECK only where the value drives behavior (`contracts.type`, `contract_versions.lifecycle`), a Kotlin enum as the whitelist otherwise; wholesale-replace JSON documents in `TEXT` (the version's `findings` snapshot); envelope columns beside a document that never enter it (V12's `source_url`/`last_synced_at`/`synced_content`).

### Soft delete (convention)

`users`, `teams`, `domains`, `systems`, `contracts` and `contract_versions` are **soft-deleted** (deleting a contract soft-deletes its versions in the same transaction, so the pair frees its identities as one unit) — rows are flagged, never physically removed; every business entity follows the same convention. Only join/audit/detail tables (today: `revoked_tokens`, a pure token registry, `user_disabled_features`, a pure flag join whose PUT is a wholesale replace, and `team_members` (V6) and `contract_subscriptions` (V13), pure membership joins, and `contract_events` (V11), the immutable history itself — none carry history worth keeping, or ARE the history) hard-delete — a new hard-delete table needs a documented justification, exactly like Lettuce's exceptions list. To add soft-delete to a new entity, follow the established pattern (reference implementation: `users/UserService.kt`; Toadie's `catalog/CatalogFileService.kt` shows the full CRUD shape incl. the delete route):

1. **Migration** — `marked_as_deleted BOOLEAN NOT NULL DEFAULT FALSE` in the CREATE (a retrofit adds the column plus `CREATE INDEX idx_<t>_marked_as_deleted ON <t>(marked_as_deleted);`).
2. **Exposed table** — declare the table `: UIntIdTable("…"), SoftDeletable` with `override val markedAsDeleted = bool("marked_as_deleted").default(false)`; the ONE `active()` predicate, `nowMillis()`, `activeCountsBy` and `requireActive` come from `infra/db/SoftDelete.kt` (no private copies — the checkup removed seven).
3. **Filter every read** — `read`, `list`, `count`, and any lookup (e.g. `findWithIdByEmail`) get `… and active()`. Apply it in the shared list predicate so the `count()` (total) and the row select stay consistent.
4. **`delete` flips the flag** — `update({ (id eq id) and (markedAsDeleted eq false) }) { it[markedAsDeleted] = true }`, returning the affected-row `Int`; guard `update` mutations the same way. The route maps `0 → 404` (the `orNotFound` helper), so a missing-or-already-deleted row is `404` (not `204`) and delete stays idempotent in effect — `UserService.deleteGuarded` shows the shape, adding the last-admin check inside the same transaction.
5. **Routes need no special-casing** — they key `404`/`204`/`NoContent` off the row-count and the `active()`-filtered `read`.

**Freeing a unique business field on delete.** To let a value be reused once its holder is soft-deleted, use a **partial unique index** over active rows instead of a global `UNIQUE`: `CREATE UNIQUE INDEX uq_<t>_<col>_active ON <t>(<col>) WHERE NOT marked_as_deleted;`. Skip the Exposed `.uniqueIndex()` on that column (Exposed defs are query-only — the DB enforces it). A clash with an **active** row still raises `23505 → 409` (mapped centrally in `plugins/ErrorHandling.kt`, which names WHAT clashed per constraint — extend `UNIQUE_CONSTRAINT_DETAILS` when adding a partial unique index). In place today: `users.email` (`uq_users_email_active`, `V1`) the team name (`uq_teams_name_active`, `V6` — an expression index over `LOWER(name)`), the domain name (`uq_domains_name_active`, `V7`), the per-domain system name (`uq_systems_domain_name_active`, `V8` — `(domain_id, LOWER(name))`) and the per-system environment name (`uq_environments_system_name_active`, `V15`). The contracts feature adds `uq_contracts_system_name_active` (`(system_id, LOWER(name))`), `uq_contract_versions_version_active` (`(contract_id, version)`) — each with its `UNIQUE_CONSTRAINT_DETAILS` wording.

**Parent/reference locking.** Active-parent validation and the child/reference write are one
transaction. Attach and move paths select the destination parent `FOR KEY SHARE`; parent delete
paths first materialize the active parent `FOR UPDATE`, then count active children in a separate
statement before flipping the soft-delete flag. Under PostgreSQL READ COMMITTED this gives both
orders a deterministic result: an attach that locked first commits before delete recounts and
rejects (or, for system-owned Environments, cascades them), while a delete that locked first makes
the later attach recheck the now-deleted parent and answer 400. The shared helpers live in
`infra/db/SoftDelete.kt`; ordinary `requireActive` reads deliberately remain unlocked. Current
locked references are System → Domain, Contract → System and owner Team, Environment → System,
plus ownership transfer's destination Team. User references remain ordinary active checks.
Import preflight stays unlocked because it precedes document checking; the real atomic item calls
the locked Contract create again. Parent deletion locks Domain, System, and Team rows before their
child counts. Roster mutations keep their existing membership locks and do not take the Team
parent-delete lock, avoiding a reversed lock order with contract authorization.

**`infra/db/Sql.kt`** (ported from Lettuce with the first list endpoint): `containsNormalized` — the case- AND accent-insensitive substring filter over `public.unaccent` (V4); every per-column substring filter MUST use it. Also `orVanished` (post-commit read-back guard → 500, for every create that reads its row back). Lettuce's `requireValidReferences` (client-supplied-FK failures → 400) was dropped as unused — re-port it with the first route that takes a client-supplied foreign key; Lettuce's JSON-path helpers (`jsonArrayContains`, `jsonTextEqualsFolded`, `jsonObjectHasKey`, `jsonObjectValueIn`) sat here consumerless for four milestones and were removed at the checkup — re-port one with its first consumer, never speculatively. The first one is back: `jsonArrayHasElementWhere` (the Errors report, `contracts/ContractErrors.kt`) renders `EXISTS (SELECT 1 FROM jsonb_array_elements(CAST(col AS jsonb)) e WHERE TRUE AND (e->>'field') IN (?, …) …)` over `contract_versions.findings` — one `IN` clause per non-empty severity/source list, an empty list lifting its own clause (the "any" case a facet dimension needs). Same per-row `CAST` trade-off as the removed helpers: fine at this scale, and the day it needs an index `findings` gets a denormalized column instead — until then `ContractErrorService`'s predicate runs a Kotlin-mirrored pre-guard over the denormalized `check_errors/warnings/infos` counts first, so the jsonb parse only runs on rows that can plausibly match.

**`infra/db/EventLog.kt` + `JsonParams.kt`** (Lettuce's shared per-record audit-event machinery, in Toadie's paged variant): a feature declares `object XEvents : EventLogTable("x_events", "x_id", XTable)` — an FK to the owning record, the acting `user_id`, a server-set `created_at`, `event_type VARCHAR(40)` (no CHECK — the Kotlin enum is the whitelist) and a `params TEXT` JSON `Map<String,String>` — and keeps only its typed `create`/`listFor` wrapper. The one clone is `contracts/ContractEvents.kt`: `contract_events` (the contract's structural history — created/updated/owner-changed/deleted, version created/content-updated/transitioned/deleted, imported) **Events are stored STRUCTURALLY so the SPA localizes them: no rendered string is ever stored**, and never document text — a content change is recorded as the FACT of the change (plus the version), never the text. `listFor` is PAGED (`EventLogPage`), because a contract's event count is unbounded (see `.claude/docs/list-endpoints.md`). Rows are IMMUTABLE: minted as a side-effect of the mutations, with no create/update/delete API.

**Consistency model (mutations vs. events vs. notifications) — deliberate.** A business mutation and its history event do **not** share one transaction, by design (Lettuce's shape, adopted wholesale rather than varied): the service method commits the mutation in its own `suspendTransaction` and returns what changed, then the ROUTE calls `contracts/ContractActivity.record` (Lettuce's order: the followers' notifications through `NotificationService.createAll` — one transaction per fan-out, the actor excluded — THEN the history event via `EventLog.create`, own transaction) beside the `audit(...)` line it already emits, and responds. Consequence, accepted at this app's scale (single instance, small payloads, low contention): a failure after the mutation commit yields a `500` with the state already changed and the event missing. Do **not** "fix" individual routes toward atomicity piecemeal — that would fork the convention; if audit-grade history or a multi-instance deployment ever becomes a requirement, revisit wholesale (a transaction-aware unit of work, or an outbox) as its own project.

**The try-it feature stores nothing** (`contracts/tryit/`, milestone 3c): a try's observation and its conformance report are computed per call and answered, never written — not to `contract_versions`, not to `contract_events` (an observation is not a change to the contract), only to the security audit log. The one persistence it touches is a READ of the environment's decrypted targets (`EnvironmentService.resolveTarget`); the SQL leg opens its own short-lived JDBC connection to the ENVIRONMENT's database (read-only, `statement_timeout`, rolled back) — never the app's own pool or R2DBC.

**Inference stores nothing either** (`contracts/infer/`, release 0.8.0): `POST /api/v1/contracts/infer` derives a draft document from samples and answers it in the response body — never `contract_versions`, never `contract_events`, no audit (the pure POST touches no contract at all). The draft only reaches the database once the caller pastes it into the ordinary version-create/import path, which runs every rule (the writer guard, the SemVer/lifecycle checks, the two-tier validation) exactly as for a typed document.

### Not yet ported from Lettuce / Toadie

Nothing remains on the persistence list — notifications arrived with milestone 3 in Lettuce's exact shape (see the consistency model above); new subsystems arrive with their own paragraph here.

### Contract mutation consistency

Contract and version mutations lock the active parent contract before inspecting mutable guards.
That shared order serializes latest-version recomputation, lifecycle transitions, deletion,
ownership transfer and source changes. The current writer guard also holds the relevant roster
row through the write; see `authorization.md`.

The checks pipeline runs outside these locks. A prepared save includes the report and the exact
published baseline identity (ACTIVE or DEPRECATED) used to produce it; the final transaction rejects a changed baseline or
lifecycle with `409`. Recheck also compares the checked content hash. Sync compares its source
reference and accepts the client's optional expected `sourceUrl`, binding the SPA's fetched copy
to that reference. Ordinary editable-content replacements remain last-write-wins; these context
guards do not implement `If-Match`.

An import checks each document once and inserts its new contract and version in one transaction;
an invalid source or failed version insert leaves no empty contract behind. The batch is still
per-item, and its response counts/findings come from the saved result. Lifecycle transitions
refresh only the local ODCS status mismatch and counts, preserving other findings and the
checker-completeness timestamp. No checker call runs inside that transition transaction.

### Release-line persistence (0.9.0)

The additive release-line migration creates per-contract major-line metadata and backfills
nondeleted version majors with UNSPECIFIED support and automatic recommendations. A partial
unique version index ignores build metadata when enforcing SemVer identity. First-version and
line creation commit together. Policy changes, version transitions that clear a recommendation
pin, and parent deletion use the same contract lock; deleting the last draft retains its line.
The line's effective recommendation is computed from ACTIVE stable versions, independently of
the global highest-version pointer. See `release-lines.md` for the complete rules.

### Toadie usage persistence (0.10.0)

V17 adds soft-deleted encrypted connection configuration and refresh metadata, replaceable
sanitized snapshot rows, and contract-to-remote-API mappings. Snapshot rows are a derived cache;
links are hard-deleted membership rows like contract subscriptions, with explicit changes retained
in ContractActivity history. These are deliberate exceptions to business-entity soft deletion. Remote numeric IDs are strings scoped
to an immutable connection identity. Link writes use the contract writer lock; refresh performs
I/O outside transactions and publishes only for the claimed configuration revision. Failure
retains the last complete observation. Explicit link changes use ContractActivity; automatic
refresh is an observation, not a contract mutation. See `toadie-integration.md`.

V22 adds nullable `toadie_connections.remote_revision` (BIGINT); existing caches start unverified. Full
scans publish rows and revision together. Same-revision confirmations only update refresh
status and `lastSuccessAt`, retaining the rows. Both paths lock the active connection and check
its configuration revision and claim token; confirmations also require the established cached
revision. Mapping changes invalidate rows and revision, while explicit API-key replacement
invalidates only the revision. See the integration reference for restore/rollback handling.

### Lifecycle-plan persistence (0.11.0)

V18 adds advisory dates, replacement IDs and plain-text migration guidance to release lines.
Replacement IDs survive target soft deletion; availability is computed at read time. The same
migration adds nullable `notifications.deduplication_key` with a unique recipient/key index,
including soft-deleted rows. Scheduled deadline notifications use that row as their delivery
ledger, inserting inside the current source-contract-locked transaction. This atomic dedup
is specific to scheduled delivery; it does not change the post-commit ContractActivity model
for user mutations. No new business tables or external writes are introduced. See
`release-lines.md` for reminder windows and upgrade behavior.

### Credential revision (V20)

`users.credential_revision` is an internal nonnegative BIGINT generation, initially zero.
Every password update/reset and conditional bootstrap rotation increments it atomically in
SQL with the hash update. It is carried in signed refresh tokens and pending MFA challenges;
refresh/MFA acceptance compares it with the current user row. It is not exposed in user DTOs.
`password_changed_at` remains a timestamp, not an authorization boundary. Pre-0.14.1 refresh
tokens lack the generation and require a fresh sign-in after deployment; existing access
tokens retain their usual expiry. Stop/drain the old application before starting the new server, whose Flyway bootstrap applies
V20 before serving requests. Do not mix old/new replicas: old code does not advance the
revision. The supplied single-instance Kubernetes Deployment uses `Recreate` to enforce this
cutover, with brief downtime; Compose replaces its single app container. Do not roll back to
pre-0.14.1 password writers while revision-bearing refresh tokens remain valid.

**Migration-report reads.** The release-line migration report owns a read-only REPEATABLE_READ
transaction over the active contract, saved release-line projection and cached Toadie links,
connection and snapshot entities. Shared transaction-scoped helpers reuse existing mappings;
no unrelated public service transaction defines the report boundary. This cross-feature read
keeps cache metadata and service rows consistent during concurrent refresh publication without
locking writers or performing any remote request.
