package ch.nokillswit.toadie

import ch.nokillswit.audit.audit
import ch.nokillswit.authz.NotFoundException
import ch.nokillswit.authz.caller
import ch.nokillswit.authz.orNotFound
import ch.nokillswit.authz.requireAdmin
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.parsePaging
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

internal fun Route.registryRoutes(service: ToadieService) {
    get<ToadieConnectionsRoute.Id.RegistryCandidates> { route ->
        requireAdmin(call.caller())
        val params = call.request.queryParameters
        val kind = params.optionalEnum<ToadieRegistryKind>("kind")
            ?: throw BadRequestException("kind must be DOMAIN, SYSTEM or TEAM")
        val paging = call.parsePaging(setOf("id", "title"))
        val result = service.registryCandidates(route.parent.id, kind, params.optionalString("q"), paging)
            .orNotFound("Toadie connection")
        call.respond(HttpStatusCode.OK, result)
    }
    post<ToadieConnectionsRoute.Id.RegistrySync.Preview> { route ->
        requireAdmin(call.caller())
        val request = call.receive<ToadieRegistryPreviewRequest>()
        val preview = service.previewRegistrySync(route.parent.parent.id, request).orNotFound("Toadie connection")
        call.respond(HttpStatusCode.OK, preview)
    }
    post<ToadieConnectionsRoute.Id.RegistrySync> { route ->
        val caller = call.caller()
        requireAdmin(caller)
        val request = call.receive<ToadieRegistryApplyRequest>()
        val result = service.applyRegistrySync(route.parent.id, request).orNotFound("Toadie connection")
        result.items.forEach { item ->
            audit(
                "toadie_registry.synced", "byUserId" to caller.userId.toLong(),
                "connectionId" to route.parent.id.toLong(), "kind" to result.kind.name,
                "entityId" to item.entityId, "localId" to item.localId.toLong(), "action" to item.action.name,
            )
        }
        call.respond(HttpStatusCode.OK, result)
    }
}

/** Shared ADMIN-before-lookup boundary for the three registry source resources. */
internal suspend fun ApplicationCall.detachRegistrySource(kind: ToadieRegistryKind, localId: UInt) {
    val caller = caller()
    requireAdmin(caller)
    if (!application.attributes[ToadieServiceKey].detachRegistrySource(kind, localId)) {
        throw NotFoundException("Registry record not found")
    }
    audit(
        "toadie_registry.detached", "byUserId" to caller.userId.toLong(),
        "kind" to kind.name, "localId" to localId.toLong(),
    )
    respond(HttpStatusCode.NoContent)
}
