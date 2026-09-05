package ch.nokillswit.contracts.tryit

import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.contracts.ContractServiceKey
import ch.nokillswit.contracts.ContractVersionServiceKey
import ch.nokillswit.contracts.ContractsRoute
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.ParseOutcome
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * The try-it family under a version (milestone 3c). The catalog is a pure read of the stored
 * document; the try POSTs (HTTP, Kafka, SQL) land leg by leg and share one preamble — the
 * version, the environment, the system match, the target's presence, the type match.
 */
fun Application.configureTryRoutes() {
    val contractService = attributes[ContractServiceKey]
    val versionService = attributes[ContractVersionServiceKey]

    routing {
        authenticate {
            get<ContractsRoute.Id.Versions.Vid.Try> { route ->
                call.caller()
                val contractId = route.parent.parent.parent.id
                val version = versionService.read(contractId, route.parent.vid).orNotFound("Version")
                val type = contractService.typeOf(contractId).orNotFound("Contract")
                // A stored document parsed at store time; a legacy row that no longer does offers nothing to try.
                val root = (DocumentParser.parse(version.content) as? ParseOutcome.Parsed)?.root
                call.respond(HttpStatusCode.OK, if (root == null) TryCatalogResponse(type) else TryCatalog.build(type, root))
            }
        }
    }
}
