package ch.nokillswit.contracts

import ch.nokillswit.authz.caller
import ch.nokillswit.infra.paging.SortField
import ch.nokillswit.infra.paging.optionalEnum
import ch.nokillswit.infra.paging.optionalString
import ch.nokillswit.infra.paging.parsePaging
import ch.nokillswit.infra.paging.toPage
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.auth.authenticate
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
@Resource("/api/v1/version-reviews/inbox")
class ReviewInboxRoute {
    @Serializable
    @Resource("summary")
    class Summary(val parent: ReviewInboxRoute = ReviewInboxRoute())
}

private fun ApplicationCall.reviewInboxFilter(): ReviewInboxFilter {
    val params = request.queryParameters
    return ReviewInboxFilter(
        q = params.optionalString("q"),
        scope = params.optionalEnum<ReviewInboxScope>("scope") ?: ReviewInboxScope.RELATED,
        attention = params.optionalEnum<ReviewInboxAttention>("attention"),
    )
}

fun Application.configureReviewInboxRoutes() {
    val service = attributes[ReviewInboxServiceKey]
    routing {
        authenticate {
            get<ReviewInboxRoute> {
                val caller = call.caller()
                val paging = call.parsePaging(
                    REVIEW_INBOX_SORT_FIELDS,
                    listOf(SortField("requestedAt", descending = false), SortField("id", descending = false)),
                )
                val result = service.list(call.reviewInboxFilter(), paging, caller)
                call.respond(HttpStatusCode.OK, paging.toPage(result.items, result.total))
            }
            get<ReviewInboxRoute.Summary> {
                val caller = call.caller()
                call.respond(HttpStatusCode.OK, service.summary(call.reviewInboxFilter(), caller))
            }
        }
    }
}
