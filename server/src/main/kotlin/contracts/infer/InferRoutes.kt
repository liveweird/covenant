package ch.nokillswit.contracts.infer

import ch.nokillswit.authz.caller
import ch.nokillswit.contracts.ContractsRoute
import ch.nokillswit.contracts.DEFAULT_MAX_DOCUMENT_BYTES
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.routing

/**
 * The inference engine's pure POST (release 0.8.0): samples in, a draft document out — computed
 * per call and never stored, exactly like the try-it family. `POST /api/v1/contracts/infer` needs
 * only authentication; no audit (nothing about a contract changed) and no rate-limit bucket (the
 * sample-count/size checks in `validateInferRequest` plus the global request-body ceiling bound
 * the work). The observe legs (an environment pulling ONE live sample) land in a later commit.
 */
fun Application.configureInferRoutes() {
    val maxDocumentBytes = environment.config.propertyOrNull("contracts.maxDocumentBytes")?.getString()?.toLongOrNull()
        ?: DEFAULT_MAX_DOCUMENT_BYTES
    routing {
        authenticate {
            post<ContractsRoute.Infer> {
                call.caller()
                val request = call.receive<InferRequest>()
                validateInferRequest(request)
                call.respond(HttpStatusCode.OK, Inference.build(request, maxDocumentBytes))
            }
        }
    }
}
