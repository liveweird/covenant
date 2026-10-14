package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.nokillswit.auth.TokenBlocklistService
import ch.nokillswit.auth.TokenBlocklistServiceKey
import ch.nokillswit.plugins.ProblemDetail
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.application.log
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class BlocklistOutageTest {
    private fun brokenBlocklist() = TokenBlocklistService(
        R2dbcDatabase.connect(
            url = "r2dbc:postgresql://localhost:1/nonexistent",
            user = "nobody",
            password = "nobody",
        ),
    )

    @Test
    fun `a failed blocklist lookup returns a generic 500`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()
        val email = uniqueEmail("blocklist-outage")
        TestUsers.seed(email = email, password = "pw")
        val token = jsonClient().login(email, "pw").body<LoginResponse>().token

        val marker = "query-secret-${java.util.UUID.randomUUID()}"
        val logs = LogCapture(application.log.name)
        try {
            val response = jsonClient().get("/api/v1/teams?token=$marker") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("An unexpected error occurred", response.body<ProblemDetail>().detail)
            assertNotNull(logs.events.firstOrNull { it.formattedMessage.startsWith("Unhandled exception") })
            assertFalse(logs.events.any { marker in it.formattedMessage })
        } finally {
            logs.detach()
        }
    }

    @Test
    fun `wrong token type and missing jti are rejected before an unavailable blocklist`() = testApplication {
        configureApp()
        application { attributes.put(TokenBlocklistServiceKey, brokenBlocklist()) }
        startApplication()
        val email = uniqueEmail("blocklist-shape")
        TestUsers.seed(email = email, password = "pw")
        val session = jsonClient().login(email, "pw").body<LoginResponse>()
        val client = jsonClient()

        val refreshResponse = client.get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer ${session.refreshToken}")
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshResponse.status)

        val jtiLess = JWT.create()
            .withAudience("covenant-api")
            .withIssuer("http://0.0.0.0:8082/")
            .withClaim("typ", "access")
            .withExpiresAt(Date(System.currentTimeMillis() + 60_000))
            .sign(Algorithm.HMAC256("secret"))
        val jtiLessResponse = client.get("/api/v1/teams") {
            header(HttpHeaders.Authorization, "Bearer $jtiLess")
        }
        assertEquals(HttpStatusCode.Unauthorized, jtiLessResponse.status)
    }
}
