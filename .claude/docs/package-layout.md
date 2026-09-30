# Server package layout

The annotated tree of `server/src/main/kotlin/`. Read this when you need to find where a
concern lives, or before adding a new area. The flat-package idiom and the feature template
that govern it stay in the root `CLAUDE.md`.

```
ch.nokillswit
├── main.kt
├── plugins/            cross-cutting Ktor wiring (configureXxx that only `install` plugins):
│                       Http, SecurityHeaders, Monitoring, Serialization, Security (JWT),
│                       ErrorHandling (RFC 7807), OpenTelemetry, AutoHeadResponse, Resources,
│                       Routing (SPA catch-all)
│                       + Health (the public /api/v1/health and /api/v1/ready probes, after Database)
│                       + RateLimits (every per-IP bucket and its name — login, refresh, reset, MFA, try-it)
├── infra/mail/         outbound email (Lettuce's, ported): Mailer/SmtpMailer/LogMailer +
│                       LocalizedText/PasswordEmail (the recipient-language content layer) +
│                       configureMail — MAIL_TRANSPORT log/smtp/disabled, the log-transport
│                       production refusal (fail-closed), null mailer = email features 503.
│                       Consumers: self-service password reset and email MFA
├── infra/crypto/       Lettuce's encryption at rest, ported: FieldCipher (AES-256-GCM `enc:v1:` envelopes,
│                       Reencrypt.kt (reencryptRows — the boot backfill body),
│                       current + rotation key), configureCrypto (DATA_ENCRYPTION_KEY, the burned-key fail-closed
│                       check — one check per concern file), EncryptedAtRest + reencryptRows (the boot backfill
│                       registry in infra/db/Bootstrap.kt). Owners: environments/ (the try-it credentials)
├── infra/db/           Flyway bootstrap + the R2DBC connection/composition root + the seed
│                       bootstrap + SoftDelete.kt (the SoftDeletable table trait — ONE active() predicate, nowMillis(),
│                       activeCountsBy, requireActive — the checkup removed seven private copies) + …
│                       bootstrap (admin rotation, prod fail-closed) + Sql.kt (containsNormalized,
│                       orVanished) + EventLog.kt/JsonParams.kt (Lettuce's
│                       shared per-record audit-event machinery — the EventLogTable base the
│                       contract history rides — the clone is `contracts/ContractEvents.kt`)
├── infra/paging/       the shared list-endpoint machinery (PageRequest/parsePaging/applyPaging/
│                       PageResponse + the strict query-param readers) — Lettuce's, ported verbatim
├── infra/validation/   cross-feature input helpers (sanitizeSingleLine — trim + control-char 400; sanitizedDescription and
│                       requireNameAndDescription — the one name/description rule every registry enforces)
├── audit/              security audit trail: `audit(event, fields…)` → AUDIT-marked structured logs
├── authz/              CallerPrincipal + guards (requireAdmin, requireSelfOrAdmin) + typed
│                       HTTP exceptions (401/403/404/409/429/502)
├── auth/               PasswordResetEmail.kt (the async reset worker) + POST /api/v1/login (+ the email-MFA branch and /login/mfa second
│                       step — MfaChallenges/MfaEmail), /refresh, /logout + the self-service
│                       POST /api/v1/password-reset (uniform acceptance/throttling, async send-before-store,
│                       PasswordResetThrottle) + token minting + password hashing/generation
│                       + LoginThrottle + the revoked-token blocklist; login/reset/MFA in-memory
│                       state has strict configurable capacities and audited 429 saturation paths
├── users/              the user domain: ADMIN-only management CRUD (/api/v1/users list/create
│                       + {id} get/put/delete with the self-delete 403 and last-admin 409
│                       protections) + PUT /api/v1/users/{id}/password + the per-user feature
│                       flags (Feature enum + PUT {id}/features, the V5 disabled-set model;
│                       MFA is the inverted-default login-scoped flag) + the per-user
│                       language (V1: PUT {id}/language, self-or-admin — the ONE synced
│                       UI+email language; Languages.kt is the SUPPORTED_LANGUAGES whitelist)
│                       + Validation.kt
├── contracts/          THE feature reference implementation (V9–V13, V16 release lines): Contract.kt/ContractVersion.kt
│                       (DTOs, sanitizers, validateContractCreate, Ownership), ContractType.kt,
│                       Lifecycle.kt (the transition matrix + contentEditable/deletable/published),
│                       SemVer.kt (full 2.0 precedence), ContractAccess.kt (the owner-team/owner-user/
│                       ADMIN writer guard + requireOwnerAssignable), ContractJoins.kt (the contracts
│                       spine — contracts ⋈ systems ⋈ domains ⋈ the two owner OUTER joins — plus
│                       contractScope/ownerRef, factored out of ContractService so ContractErrorService
│                       shares one join and one filter instead of a second copy), ContractService.kt
│                       (Contracts table; the list/tree joins, facets (six group-bys over the shared
│                       predicate, each dimension lifted), authorizeWrite reading team_members inside the tx, the
│                       published-versions 409 on delete, export), ContractVersionService.kt
│                       (ContractVersions table; SemVer precedence uniqueness + backports, the HARD/SOFT gate, transitions,
│                       recomputeLatest, applySemverPaging shared with the errors report), ContractErrors.kt
│                       (the Errors report's DTOs + ErrorListFilter + the pure foldErrorFacets/Finding.matches
│                       fold — Toadie's `/errors` ported onto Covenant's stored findings) and
│                       ContractErrorService.kt (ContractErrorService — every ACTIVE version carrying a
│                       matching finding, paged; facets counted in memory over one select, jsonb parse
│                       guarded by the denormalized check counts), ContractEvents.kt (the EventLog clone),
│                       ContractSubscriptionService.kt
│                       (V13 followers), ContractNotifications.kt (the pure event → notification mapping),
│                       ContractActivity.kt (THE post-commit chokepoint: followers' notifications, then the
│                       history event), Links.kt (the SPA paths notifications carry), Import.kt (report &
│                       skip, dry run = the same walk without the store), Tree.kt, UrlFetch.kt (Toadie's
│                       SSRF-guarded fetch), ContractRoutes.kt + ContractVersionRoutes.kt, and checks/ —
│                       Finding.kt (severity/source vocabulary, CheckReport), DocumentParser.kt (format
│                       detection, Jackson YAML with the alias/size caps, the type gate), Metadata.kt,
│                       OpenApiValidator/AsyncApiValidator/OdcsValidator.kt, BreakingChanges.kt (the baseline +
│                       the WARN/INFO/gate settlement) with OpenApiBreaking.kt (openapi-diff-core, 3.1 through the
│                       3.0 model), AvroBreaking.kt (AsyncAPI Avro reader/writer compatibility),
│                       and OdcsBreaking.kt (hand-written; other ASYNCAPI facts come from the checker's
│                       @asyncapi/diff pass), VendoredSchemas.kt (offline
│                       networknt registries over resources/schemas), CheckerClient.kt (the sidecar
│                       client + its AttributeKey test seam), ChecksService.kt (the pipeline plus
│                       ChecksService.compatibility — the two-way breaking-change facts between an
│                       arbitrary pair, never settled/stored), Compatibility.kt (the DTOs — VersionRef,
│                       CompatibilityDirection/Report, the CompatibilityVerdict/VersionBump enums — and
│                       the pure Compatibility.bump/verdict), Checks.kt
│                       (the module reading checker.url/token/timeoutMs); and tryit/ (milestone 3c) —
│                       TryModel.kt (the catalog + report DTOs), TryCatalog.kt (what a document offers to
│                       try, pure over the tree), Conformance.kt (DocumentSchemas — the in-document offline
│                       networknt registry with the OpenAPI dialects — HttpConformance, PayloadConformance),
│                       OdcsTypes.kt (logicalType ↔ PostgreSQL type families), HttpTry.kt / SqlTry.kt /
│                       KafkaTry.kt + KafkaClients.kt (the three legs — the server calls the environment,
│                       never the SPA), PostgresRead.kt (the read-only connection prologue + SQLException
│                       classify — shared by SqlTry and the inference engine's SQL observe leg), TryRoutes.kt
│                       (GET …/{vid}/try + the four try POSTs behind the shared TryPreamble and the tryIt
│                       RateLimit bucket); and render/ (milestone 4, the
│                       reader's model behind GET …/{vid}/model) — RenderModel.kt (the @Serializable view
│                       DTOs: SchemaNode + the OpenApi/AsyncApi/Odcs family models), JsonPointers.kt,
│                       RenderBudget.kt (maxDepth 32 / maxNodes 20k → TRUNCATED markers), Nodes.kt (total
│                       accessors), SchemaWalker.kt (JSON Schema → SchemaNode, 3.0 and 2020-12 dialects, the
│                       cycle rule), AvroSchemaMapper.kt, Shared.kt, OpenApiRenderer/AsyncApiRenderer/
│                       OdcsRenderer.kt, ContractRenderer.kt (parse → type gate → dispatch; nulls omitted)
├── contracts/infer/    the inference engine (release 0.8.0): samples in, a DRAFT document out — computed
│                       per call, stored nowhere (the try-it posture). InferModel.kt (the wire DTOs —
│                       InferRequest/HttpExchangeSample/MessageBatchSample/RelationColumn/RelationSample/
│                       InferResponse — validateInferRequest, the Notes accumulator and the Inference.build
│                       dispatcher), SchemaInference.kt (JSON samples → a 2020-12/OpenAPI-3.1 JSON Schema —
│                       type merge incl. integer∪number, null → a type array, required = present AND
│                       non-null in every sample, formats asserted only when every non-null string
│                       matches, RenderBudget-bounded), PathTemplates.kt (the path-templating classifier +
│                       the preceding-segment naming rule + operationId), OpenApiInference.kt (HTTP
│                       exchanges → OpenAPI 3.1 — paths grouped by method+template, query/request-body/
│                       response/header rules, the security-scheme detector), AsyncApiInference.kt
│                       (message batches → AsyncAPI 3.0 — one channel + one send operation per batch,
│                       messages declared INLINE in the channel, CloudEvents envelope detection),
│                       OdcsInference.kt (a relation's columns and/or rows → an ODCS 3.0.2 dataset — columns
│                       win for type/required, rows add missing properties, the view-nullability and
│                       schema-qualified caveats), DocumentWriter.kt ("the ONE place Covenant writes a
│                       document — into a response body only, never stored; its custom
│                       StringQuotingChecker closes the YAML-1.1-ambiguity gaps Jackson's default leaves"),
│                       Observe.kt (ObserveHttp — HttpTry.prepareRaw + HttpTry.send reduced to one
│                       HttpExchangeSample, header NAMES + the Authorization scheme word only; ObserveKafka —
│                       KafkaTry.read reduced to the UTF-8 JSON payloads worth learning a schema from, the
│                       rest counted in one INFER_RECORDS_SKIPPED note), ObserveSql.kt (the SQL observe leg:
│                       to_regclass/pg_attribute/pg_index over PostgresRead — never SELECT *; describe one
│                       relation or list what the database offers), InferRoutes.kt (the pure POST
│                       /contracts/infer plus the four observe legs — POST …/infer/observe/http|kafka|sql|
│                       sql/relations — behind the shared tryIt RateLimit bucket, reusing TryRoutes.audited/
│                       jdbcHost). Every heuristic applied rides back as a FindingSource.INFERENCE note.
├── notifications/      in-app notifications (V14, Lettuce's, ported minus flags/email): Notification.kt (the
│                       NotificationType whitelist + DTOs), NotificationService.kt (createAll/read/seen/unseen/
│                       seenAll/soft delete/paged list on ONE predicate), NotificationRoutes.kt — /api/v1/notifications,
│                       recipient-only for everyone (ADMIN included)
├── teams/              flat teams (V6): Team.kt (DTOs + sanitizers + validateTeam*), TeamService.kt
│                       (Teams + the TeamMembers hard-delete join; paged list with name/memberId
│                       filters and active-member counts; roster read joining users; create with an
│                       initial roster; addMember/removeMember; activeTeamIdsOf — the contract writer
│                       guard's lookup), TeamRoutes.kt — GET /api/v1/teams (+ {id}) any authenticated,
│                       POST/PUT/DELETE + the members pair ADMIN only
├── domains/            the hierarchy's top level (V7): Domain.kt, DomainService.kt (paged list +
│                       listAll for the tree/pickers, active-system counts, the holds-systems 409 on
│                       delete), DomainRoutes.kt — /api/v1/domains, reads any authenticated, writes ADMIN
├── environments/       the try-it connection targets (V15): Environment.kt (DTOs — write-only passwords in,
│                       `hasPassword` out — and the decrypted EnvironmentTargets for the try services only),
│                       TargetValidation.kt (the static shape rules: http(s) base URL, bootstrap entries, SASL
│                       triplet, the JDBC parameter allow-list), EnvironmentService.kt (EncryptedAtRest —
│                       kafka_password/pg_password), EnvironmentRoutes.kt — /api/v1/environments, reads any
│                       authenticated, writes ADMIN. The registry IS the try-it SSRF control (security.md)
├── systems/            the hierarchy's middle level (V8): System.kt, SystemService.kt (joins the domain
│                       name; domainId filter; a PUT moves the system; the domain id checked inside the
│                       write's transaction), SystemRoutes.kt — /api/v1/systems, same authz split
```
