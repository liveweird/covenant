package ch.nokillswit.contracts

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.*
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/contracts/lifecycle-overview")
class LifecycleOverviewRoute {
    @Serializable
    @Resource("summary")
    class Summary(val parent: LifecycleOverviewRoute = LifecycleOverviewRoute())
}

private fun ApplicationCall.overviewFilter(): LifecycleOverviewFilter {
    val params = request.queryParameters
    return LifecycleOverviewFilter(
        ContractListFilter(domainId = params.optionalUInt("domainId"), systemId = params.optionalUInt("systemId"),
            ownerTeamId = params.optionalUInt("ownerTeamId"), ownerUserId = params.optionalUInt("ownerUserId"),
            q = params.optionalString("q"), types = params.repeatedEnum<ContractType>("type")),
        params.repeatedEnum<SupportStatus>("supportStatus"), params.optionalEnum<LifecycleDeadline>("deadline"),
        params.optionalEnum<LifecycleAttention>("attention"),
    )
}

fun Application.configureLifecycleOverviewRoutes() {
    val service = attributes[LifecycleOverviewServiceKey]
    routing {
        authenticate {
            get<LifecycleOverviewRoute> {
                val caller = call.caller()
                val paging = call.parsePaging(LIFECYCLE_OVERVIEW_SORT_FIELDS, listOf(SortField("nextDeadline", false)))
                val result = service.list(call.overviewFilter(), paging, caller)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<LifecycleOverviewRoute.Summary> {
                call.caller()
                call.respond(HttpStatusCode.OK, service.summary(call.overviewFilter()))
            }
        }
    }
}
