package ch.nokillswit.infra.db

import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.contracts.ContractEventService
import ch.nokillswit.contracts.ContractEventServiceKey
import ch.nokillswit.contracts.ContractService
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
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase

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
    attributes.put(UserServiceKey, UserService(database))
    val teamService = TeamService(database)
    attributes.put(TeamServiceKey, teamService)
    val domainService = DomainService(database)
    attributes.put(DomainServiceKey, domainService)
    attributes.put(SystemServiceKey, SystemService(database, domainService))
    // The contract services: the writer guard reads team membership (TeamService), the store
    // paths run the check pipeline (ChecksService — published by configureChecks, which
    // application.yaml therefore lists BEFORE this module).
    attributes.put(ContractServiceKey, ContractService(database, teamService))
    attributes.put(ContractVersionServiceKey, ContractVersionService(database, attributes[ChecksServiceKey]))
    attributes.put(ContractEventServiceKey, ContractEventService(database))
    attributes.put(TokenBlocklistServiceKey, TokenBlocklistService(database))
}
