package ch.nokillswit

import ch.nokillswit.auth.LoginRequest
import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.hashPassword
import ch.nokillswit.infra.db.SEED_ADMIN_EMAIL
import ch.nokillswit.infra.db.SEED_PASSWORD_HASH
import ch.nokillswit.users.User
import ch.nokillswit.users.UserRole
import ch.nokillswit.users.UserService
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * Points the app at the shared Testcontainers Postgres (with CSRF off) WITHOUT starting it —
 * callers that assert startup behavior (fail-closed checks) add their own overrides and call
 * `startApplication()` themselves. Later duplicate keys win in [MapApplicationConfig], so
 * [overrides] may replace the defaults listed first.
 */
fun ApplicationTestBuilder.configureApp(vararg overrides: Pair<String, String>) {
    // The checker sidecar never runs in the suite: the default stub answers "no findings", so a
    // clean document stores clean; a test needing findings or an outage installs its own stub
    // (`application { attributes.put(CheckerClientKey, …) }` BEFORE startApplication()).
    application {
        if (!attributes.contains(ch.nokillswit.contracts.checks.CheckerClientKey)) {
            attributes.put(ch.nokillswit.contracts.checks.CheckerClientKey, TestChecker.silent)
        }
    }
    environment {
        config = ApplicationConfig("application.yaml").mergeWith(
            MapApplicationConfig(
                "postgres.jdbcUrl" to PostgresTestSupport.jdbcUrl,
                "postgres.r2dbcUrl" to PostgresTestSupport.r2dbcUrl,
                "postgres.user" to PostgresTestSupport.user,
                "postgres.password" to PostgresTestSupport.password,
                "security.csrf.enabled" to "false",
                *overrides,
            )
        )
    }
}

suspend fun ApplicationTestBuilder.usePostgresTestcontainer() {
    configureApp()
    startApplication()
}

/**
 * Shared config for every test HTTP client: JSON (+ problem+json) negotiation and the
 * [OpenApiConformance] plugin, which validates each /api/ interaction against the OpenAPI spec.
 */
fun HttpClientConfig<*>.covenantTestClientDefaults() {
    install(ContentNegotiation) { json(); json(contentType = ContentType.parse("application/problem+json")) }
    install(OpenApiConformance)
}

fun ApplicationTestBuilder.jsonClient(): HttpClient = createClient { covenantTestClientDefaults() }

/** A unique throwaway email so tests never collide on the partial-unique active-email index. */
fun uniqueEmail(prefix: String) = "$prefix-${java.util.UUID.randomUUID()}@test"

/** A private 64-hex data-encryption key — every production-mode boot needs one (the dev default is burned). */
fun strongEncryptionKey(): String =
    java.util.UUID.randomUUID().toString().replace("-", "") + java.util.UUID.randomUUID().toString().replace("-", "")

/** POSTs [body] as JSON — the contentType+setBody ceremony, owned once. */
suspend inline fun <reified T> HttpClient.postJson(path: String, body: T): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** PUTs [body] as JSON. */
suspend inline fun <reified T> HttpClient.putJson(path: String, body: T): HttpResponse =
    put(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

/** The raw login POST — for tests asserting login behavior itself ([authedClient] wraps it). */
suspend fun HttpClient.login(email: String, password: String): HttpResponse =
    postJson("/api/v1/login", LoginRequest(email, password))

/** Logs in as [email] and returns a client that sends the bearer token on every request. */
suspend fun ApplicationTestBuilder.authedClient(email: String, password: String): HttpClient {
    val token = jsonClient().login(email, password).body<LoginResponse>().token
    return createClient {
        covenantTestClientDefaults()
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
    }
}

/** Seeds a unique throwaway user and logs them in — the standard per-test caller fixture. */
suspend fun ApplicationTestBuilder.seededClient(prefix: String, role: UserRole = UserRole.USER): HttpClient {
    val email = uniqueEmail(prefix)
    TestUsers.seed(email = email, password = "pw", role = role)
    return authedClient(email, "pw")
}

/**
 * Captures a logger's events with a Logback ListAppender (the audit trail on
 * `ch.nokillswit.audit`). Use in a try/finally with [detach]; [awaitEvent] polls for
 * asynchronously produced events.
 */
class LogCapture(loggerName: String) {
    private val logger = org.slf4j.LoggerFactory.getLogger(loggerName) as ch.qos.logback.classic.Logger
    private val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()

    init {
        appender.start()
        logger.addAppender(appender)
    }

    val events: List<ch.qos.logback.classic.spi.ILoggingEvent> get() = appender.list

    fun detach() = logger.detachAppender(appender)

    suspend fun awaitEvent(
        predicate: (ch.qos.logback.classic.spi.ILoggingEvent) -> Boolean,
    ): ch.qos.logback.classic.spi.ILoggingEvent? {
        repeat(100) {
            events.firstOrNull(predicate)?.let { return it }
            kotlinx.coroutines.delay(50)
        }
        return null
    }
}

/**
 * audit() fields travel as SLF4J key/values, not in the message text — and TYPED: ids arrive
 * as Longs, flags as Booleans, so compare with the same type the emitter used.
 */
fun ch.qos.logback.classic.spi.ILoggingEvent.hasKeyValue(key: String, value: Any?) =
    keyValuePairs?.any { it.key == key && it.value == value } == true

/** The audit-trail capture scaffold: attaches to the audit logger and always detaches. */
suspend fun <T> withAuditCapture(block: suspend (LogCapture) -> T): T {
    val capture = LogCapture("ch.nokillswit.audit")
    return try {
        block(capture)
    } finally {
        capture.detach()
    }
}

/** Bootstrap/prod-mode scaffold: whatever [block] does to the seed admin is restored after. */
suspend fun withSeedRestored(block: suspend () -> Unit) {
    try {
        block()
    } finally {
        TestSeedState.restoreSeedAccounts()
    }
}

/** Asserts the app refuses to start and that the failure cause chain mentions [messagePart]. */
suspend fun assertStartupFails(messagePart: String, start: suspend () -> Unit) {
    val failure = runCatching { start() }.exceptionOrNull()
    kotlin.test.assertNotNull(failure, "startup must fail closed")
    val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    kotlin.test.assertTrue(messagePart in messages, "unexpected startup failure: $messages")
}

/** A fresh blocklist service (own cache) over the shared container DB, with an injected clock. */
internal fun newTokenBlocklistService(clock: () -> Long): ch.nokillswit.auth.TokenBlocklistService =
    ch.nokillswit.auth.TokenBlocklistService(sharedTestDatabase, clock)

/** The shared test database — for fixtures that construct a service by hand (the encryption tests). */
fun sharedDatabaseForTests(): R2dbcDatabase = sharedTestDatabase

private val sharedTestDatabase: R2dbcDatabase by lazy {
    R2dbcDatabase.connect(
        url = PostgresTestSupport.r2dbcUrl,
        user = PostgresTestSupport.user,
        password = PostgresTestSupport.password,
    )
}

object TestUsers {
    val service: UserService by lazy { UserService(sharedTestDatabase) }

    suspend fun seed(
        email: String,
        password: String,
        name: String = "Test",
        role: UserRole = UserRole.ADMIN,
        language: String = "en",
    ): UInt = service.create(
        User(
            name = name,
            email = email,
            passwordHash = hashPassword(password, cost = 4),
            role = role,
            language = language,
        )
    )

    /** Direct soft-delete for fixtures needing to bypass the endpoint's guards. */
    suspend fun softDelete(id: UInt) {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.id eq id }) {
                it[UserService.Users.markedAsDeleted] = true
            }
        }
    }

    /**
     * Runs [block] while the users in [soloAdminIds] are the ONLY active admins — every other
     * active ADMIN row (the seed admin and other tests' fixtures included) is temporarily
     * soft-deleted and restored in a finally. Backs the last-admin-protection tests, which
     * need `countActiveAdmins()` to be exact in the shared container.
     */
    suspend fun withSoloAdmins(soloAdminIds: Set<UInt>, block: suspend () -> Unit) {
        val parked: List<UInt> = suspendTransaction(sharedTestDatabase) {
            val others = UserService.Users.selectAll()
                .where {
                    (UserService.Users.role eq UserRole.ADMIN.name) and
                        (UserService.Users.markedAsDeleted eq false)
                }
                .map { it[UserService.Users.id].value }
                .toList()
                .filter { it !in soloAdminIds }
            UserService.Users.update({ UserService.Users.id inList others }) {
                it[UserService.Users.markedAsDeleted] = true
            }
            others
        }
        try {
            block()
        } finally {
            suspendTransaction(sharedTestDatabase) {
                UserService.Users.update({ UserService.Users.id inList parked }) {
                    it[UserService.Users.markedAsDeleted] = false
                }
            }
        }
    }
}

object TestSeedState {
    suspend fun restoreSeedAccounts() {
        suspendTransaction(sharedTestDatabase) {
            UserService.Users.update({ UserService.Users.email eq SEED_ADMIN_EMAIL }) {
                it[UserService.Users.passwordHash] = SEED_PASSWORD_HASH
                it[UserService.Users.markedAsDeleted] = false
                it[UserService.Users.passwordChangedAt] = 0
                // A test flipping the seed admin's language must not leak into other tests.
                it[UserService.Users.language] = "en"
            }
        }
    }
}

/** Direct team fixtures — roster reads past the routes (the soft-delete assertions) and quick seeding. */
object TestEnvironments {
    val service: ch.nokillswit.environments.EnvironmentService by lazy {
        ch.nokillswit.environments.EnvironmentService(
            sharedTestDatabase,
            ch.nokillswit.infra.crypto.FieldCipher(TEST_DATA_ENCRYPTION_KEY),
        )
    }

    /** The application.yaml dev default — the key every test app boots with, so this service reads what the app wrote. */
    const val TEST_DATA_ENCRYPTION_KEY = "ef9b766b3b3dc805220826e796b1b907d7b7fb55048940e1b730af61b7f17aa4"

    data class RawRow(val kafkaPassword: String?, val pgPassword: String?, val markedAsDeleted: Boolean)

    suspend fun rawRow(id: UInt): RawRow = suspendTransaction(sharedTestDatabase) {
        val e = ch.nokillswit.environments.EnvironmentService.Environments
        e.selectAll().where { e.id eq id }
            .map { RawRow(it[e.kafkaPassword], it[e.pgPassword], it[e.markedAsDeleted]) }
            .toList().single()
    }

    /** A legacy row with PLAINTEXT passwords — what a pre-encryption deployment would hold; the bootstrap must wrap it. */
    suspend fun seedLegacyPlaintext(systemId: UInt, name: String, pgPassword: String): UInt = suspendTransaction(
        sharedTestDatabase,
    ) {
        val e = ch.nokillswit.environments.EnvironmentService.Environments
        val now = System.currentTimeMillis()
        e.insert {
            it[e.systemId] = systemId
            it[e.name] = name
            it[e.pgJdbcUrl] = "jdbc:postgresql://db.internal:5432/app"
            it[e.pgUsername] = "reader"
            it[e.pgPassword] = pgPassword
            it[e.createdAt] = now
            it[e.updatedAt] = now
        }[e.id].value
    }
}

object TestNotifications {
    val service: ch.nokillswit.notifications.NotificationService by lazy {
        ch.nokillswit.notifications.NotificationService(sharedTestDatabase)
    }
}

object TestTeams {
    val service: ch.nokillswit.teams.TeamService by lazy { ch.nokillswit.teams.TeamService(sharedTestDatabase) }

    suspend fun seed(name: String, memberIds: List<UInt> = emptyList()): UInt =
        service.create(ch.nokillswit.teams.TeamCreateRequest(name = name, memberIds = memberIds))

    data class RawTeam(val id: UInt, val name: String, val markedAsDeleted: Boolean)

    suspend fun rawRows(): List<RawTeam> = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.teams.TeamService.Teams
        t.selectAll().map { RawTeam(it[t.id].value, it[t.name], it[t.markedAsDeleted]) }.toList()
    }

    suspend fun rawMemberIds(teamId: UInt): Set<UInt> = suspendTransaction(sharedTestDatabase) {
        val m = ch.nokillswit.teams.TeamService.TeamMembers
        m.selectAll().where { m.teamId eq teamId }.map { it[m.userId].value }.toList().toSet()
    }
}

/** Direct registry fixtures for the Domain → System hierarchy (unique names per test). */
object TestDomains {
    val service: ch.nokillswit.domains.DomainService by lazy { ch.nokillswit.domains.DomainService(sharedTestDatabase) }

    suspend fun seed(name: String, description: String? = null): UInt =
        service.create(ch.nokillswit.domains.DomainRequest(name = name, description = description))

    suspend fun rawDeleted(id: UInt): Boolean = suspendTransaction(sharedTestDatabase) {
        val t = ch.nokillswit.domains.DomainService.Domains
        t.selectAll().where { t.id eq id }.map { it[t.markedAsDeleted] }.toList().single()
    }
}

object TestSystems {
    val service: ch.nokillswit.systems.SystemService by lazy {
        ch.nokillswit.systems.SystemService(sharedTestDatabase, TestDomains.service)
    }

    suspend fun seed(domainId: UInt, name: String, description: String? = null): UInt =
        service.create(ch.nokillswit.systems.SystemRequest(domainId = domainId, name = name, description = description))
}

/** Checker stubs: silent (no findings), canned findings, or a sidecar outage. */
object TestChecker {
    class Stub(private val findings: List<ch.nokillswit.contracts.checks.Finding>, private val fail: Boolean = false) :
        ch.nokillswit.contracts.checks.CheckerClient {
        var calls = 0
        /** What the last call carried as the breaking-change baseline (ASYNCAPI only, by the pipeline's rule). */
        var lastPreviousContent: String? = null
        override suspend fun check(
            type: ch.nokillswit.contracts.ContractType,
            content: String,
            previousContent: String?,
        ): ch.nokillswit.contracts.checks.CheckerResponse {
            calls++
            lastPreviousContent = previousContent
            if (fail) throw ch.nokillswit.contracts.checks.CheckerUnavailableException("stub outage")
            return ch.nokillswit.contracts.checks.CheckerResponse(findings)
        }
    }

    val silent = Stub(emptyList())

    fun lint(
        code: String = "info-contact",
        severity: ch.nokillswit.contracts.checks.Severity = ch.nokillswit.contracts.checks.Severity.WARN,
    ) = Stub(
        listOf(
            ch.nokillswit.contracts.checks.Finding(
                severity,
                ch.nokillswit.contracts.checks.FindingSource.LINT,
                code,
                "stub finding $code",
                "/info",
                2,
                1,
            ),
        ),
    )

    fun down() = Stub(emptyList(), fail = true)
}

/** Direct contract fixtures: a system to hang contracts on, and contract/version seeding past the routes. */
object TestContracts {
    /** A fresh domain + system per call — contract names are unique WITHIN a system, so tests isolate by system. */
    suspend fun seedSystem(prefix: String): UInt {
        val domain = TestDomains.seed("$prefix-dom-${java.util.UUID.randomUUID().toString().substring(0, 8)}")
        return TestSystems.seed(domain, "$prefix-sys-${java.util.UUID.randomUUID().toString().substring(0, 8)}")
    }
}
