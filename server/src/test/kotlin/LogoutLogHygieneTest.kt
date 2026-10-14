package ch.nokillswit

import ch.nokillswit.auth.LoginResponse
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.log
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogoutLogHygieneTest {
    @Test
    fun `logout parse failures log only an exception type`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout-log")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()
        val marker = "logout-secret-${UUID.randomUUID()}"
        val logger = LoggerFactory.getLogger(application.log.name) as Logger
        val originalLevel = logger.level
        logger.level = Level.DEBUG
        val capture = LogCapture(logger.name)
        try {
            val response = client.post("/api/v1/logout") {
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                contentType(ContentType.Application.Json)
                setBody("{ invalid $marker")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val event = capture.events.firstOrNull { it.formattedMessage.startsWith("Logout body unparsable") }
            assertNotNull(event)
            assertTrue(event.throwableProxy == null)
            assertFalse(capture.events.any { marker in it.formattedMessage })
        } finally {
            logger.level = originalLevel
            capture.detach()
        }
    }

    @Test
    fun `invalid refresh tokens do not attach decoder exceptions to logs`() = testApplication {
        usePostgresTestcontainer()
        val email = uniqueEmail("logout-refresh-log")
        TestUsers.seed(email = email, password = "pw")
        val client = jsonClient()
        val session = client.login(email, "pw").body<LoginResponse>()
        val marker = "invalid-refresh-${UUID.randomUUID()}"
        val logger = LoggerFactory.getLogger(application.log.name) as Logger
        val originalLevel = logger.level
        logger.level = Level.DEBUG
        val capture = LogCapture(logger.name)
        try {
            val response = client.post("/api/v1/logout") {
                header(HttpHeaders.Authorization, "Bearer ${session.token}")
                contentType(ContentType.Application.Json)
                setBody("""{"refreshToken":"$marker"}""")
            }
            assertEquals(HttpStatusCode.NoContent, response.status)
            val event = capture.events.firstOrNull { it.formattedMessage.startsWith("Logout refresh token invalid") }
            assertNotNull(event)
            assertTrue(event.throwableProxy == null)
            assertFalse(capture.events.any { marker in it.formattedMessage })
        } finally {
            logger.level = originalLevel
            capture.detach()
        }
    }
}
