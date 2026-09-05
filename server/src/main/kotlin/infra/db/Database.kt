package ch.nokillswit.infra.db

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.contracts.ContractEventService
import ch.nokillswit.contracts.ContractEventServiceKey
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractUrlFetcherKey
import ch.nokillswit.contracts.ContractUrlFetcher
import ch.nokillswit.contracts.ContractImporterKey
import ch.nokillswit.contracts.ContractImporter
import ch.nokillswit.contracts.ContractServiceKey
import ch.nokillswit.contracts.ContractVersionService
import ch.nokillswit.contracts.ContractVersionServiceKey
import ch.nokillswit.contracts.checks.ChecksServiceKey
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.domains.DomainService
import ch.nokillswit.domains.DomainServiceKey
import ch.nokillswit.systems.SystemService
import ch.nokillswit.systems.SystemServiceKey
import ch.nokillswit.teams.TeamService
import ch.nokillswit.teams.TeamServiceKey
import ch.nokillswit.users.UserService
import ch.nokillswit.users.UserServiceKey
import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import ch.nokillswit.notifications.NotificationServiceKey
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.contracts.ContractSubscriptionServiceKey
import ch.nokillswit.contracts.ContractSubscriptionService
import ch.nokillswit.contracts.ContractActivityKey
import ch.nokillswit.contracts.ContractActivity
import ch.nokillswit.infra.crypto.FieldCipherKey
import ch.nokillswit.environments.EnvironmentServiceKey
import ch.nokillswit.environments.EnvironmentService

/** The connected database itself — read by the readiness probe (plugins/Health.kt); services get it injected. */
val R2dbcDatabaseKey = AttributeKey<R2dbcDatabase>("R2dbcDatabase")

/**
 * The DI composition root: connects the one R2DBC database and publishes every service into
 * [Application.attributes]. Feature modules read their services back via the AttributeKey —
 * application.yaml runs this module before any route module, so the keys are always present.
 */
suspend fun Application.configureDatabase() {
    val database = R2dbcDatabase.connect(
        url = environment.config.property("postgres.r2dbcUrl").getString(),
        user = environment.config.property("postgres.user").getString(),
        password = environment.config.property("postgres.password").getString(),
    )
    val userService = UserService(database)
    attributes.put(R2dbcDatabaseKey, database)
    attributes.put(UserServiceKey, userService)
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    val domainService = DomainService(database)
    attributes.put(DomainServiceKey, domainService)
    attributes.put(SystemServiceKey, SystemService(database, domainService))
    // The try-it environments: credentials encrypted at rest with the FieldCipher configureCrypto published.
    attributes.put(EnvironmentServiceKey, EnvironmentService(database, attributes[FieldCipherKey]))
    // The contract services: the writer guard reads team membership (TeamService), the store
    // paths run the check pipeline (ChecksService — published by configureChecks, which
    // application.yaml therefore lists BEFORE this module).
    val contractService = ContractService(database, teamService)
    attributes.put(ContractServiceKey, contractService)
    val versionService = ContractVersionService(database, attributes[ChecksServiceKey])
    attributes.put(ContractVersionServiceKey, versionService)
    // The import pipeline and the SSRF-guarded fetcher: stateless collaborators, built here rather than
    // in a route file so every service has ONE home (a test pre-puts ContractUrlFetcherKey to aim the
    // fetch at a fixture — the routes read the key per request).
    attributes.put(ContractImporterKey, ContractImporter(contractService, versionService, attributes[ChecksServiceKey]))
    attributes.put(ContractUrlFetcherKey, ContractUrlFetcher())
    val eventService = ContractEventService(database)
    attributes.put(ContractEventServiceKey, eventService)
    // Followers + their notifications: the routes record every contract mutation through
    // ContractActivity (notifications, then the history event — see persistence.md).
    val subscriptionService = ContractSubscriptionService(database)
    attributes.put(ContractSubscriptionServiceKey, subscriptionService)
    val notificationService = NotificationService(database)
    attributes.put(NotificationServiceKey, notificationService)
    attributes.put(
        ContractActivityKey,
        ContractActivity(contractService, userService, subscriptionService, notificationService, eventService),
    )
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
}
