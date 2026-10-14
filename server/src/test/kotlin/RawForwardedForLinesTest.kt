package ch.nokillswit

import ch.nokillswit.users.UserRole
import io.ktor.server.netty.EngineMain
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reproduces physically distinct forwarded-for lines against Netty; ktor-client merges them. */
class RawForwardedForLinesTest {

    @Test
    fun `two distinct X-Forwarded-For wire lines fold into one in hop order`() {
        val server = EngineMain.createServer(
            arrayOf(
                "-port=0",
                "-P:ktor.development=true",
                "-P:postgres.jdbcUrl=${PostgresTestSupport.jdbcUrl}",
                "-P:postgres.r2dbcUrl=${PostgresTestSupport.r2dbcUrl}",
                "-P:postgres.user=${PostgresTestSupport.user}",
                "-P:postgres.password=${PostgresTestSupport.password}",
                "-P:security.csrf.enabled=false",
                "-P:http.behindProxy=true",
                "-P:security.rateLimit.loginPerMinute=10",
            ),
        )
        val seeded = mutableListOf<UInt>()
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().first().port }

            fun rawLoginStatus(clientLine: String, proxyLine: String): Int {
                val email = "xff-raw-${UUID.randomUUID()}@test"
                seeded += runBlocking { TestUsers.seed(email, "the-right-password", role = UserRole.USER) }
                Socket("127.0.0.1", port).use { socket ->
                    val body = """{"email":"$email","password":"wrong"}"""
                    val bytes = body.toByteArray()
                    val request = buildString {
                        append("POST /api/v1/login HTTP/1.1\r\n")
                        append("Host: 127.0.0.1:$port\r\n")
                        append("Content-Type: application/json\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("X-Forwarded-For: $clientLine\r\n")
                        append("X-Forwarded-For: $proxyLine\r\n")
                        append("Connection: close\r\n")
                        append("\r\n")
                        append(body)
                    }
                    socket.soTimeout = 10_000
                    socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                    socket.getOutputStream().flush()
                    val statusLine = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                        ?: error("no response from the server")
                    return statusLine.split(" ")[1].toInt()
                }
            }

            repeat(10) { i ->
                assertEquals(401, rawLoginStatus("10.9.$i.1", "203.0.113.7"))
            }
            assertEquals(429, rawLoginStatus("10.9.99.1", "203.0.113.7"))
            assertEquals(401, rawLoginStatus("10.9.99.1", "203.0.113.8"))
        } finally {
            server.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
            runBlocking { seeded.forEach { TestUsers.softDelete(it) } }
        }
    }
}
