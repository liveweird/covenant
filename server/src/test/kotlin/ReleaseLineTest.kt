package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractEventPageResponse
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.ReleaseLinePageResponse
import ch.nokillswit.contracts.ReleaseLineResponse
import ch.nokillswit.contracts.ReleaseLineUpdateRequest
import ch.nokillswit.contracts.SupportStatus
import ch.nokillswit.contracts.TransitionRequest
import ch.nokillswit.contracts.VersionCreateRequest
import ch.nokillswit.contracts.VersionPageResponse
import ch.nokillswit.contracts.VersionResponse
import ch.nokillswit.plugins.ProblemDetail
import ch.nokillswit.notifications.NotificationPageResponse
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReleaseLineTest {
    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private suspend fun createContract(admin: io.ktor.client.HttpClient, prefix: String): ContractResponse {
        val teamId = TestTeams.seed(name("team"))
        return admin.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(TestContracts.seedSystem(prefix), ContractType.OPENAPI, name(prefix), ownerTeamId = teamId),
        ).body()
    }

    private suspend fun activate(client: io.ktor.client.HttpClient, contract: ContractResponse, version: String): VersionResponse {
        val created = client.postJson(
            "/api/v1/contracts/${contract.id}/versions",
            VersionCreateRequest(version, ContractFixtures.openApi),
        ).body<VersionResponse>()
        client.postJson(
            "/api/v1/contracts/${contract.id}/versions/${created.id}/transition",
            TransitionRequest(Lifecycle.PROPOSED),
        )
        return client.postJson(
            "/api/v1/contracts/${contract.id}/versions/${created.id}/transition",
            TransitionRequest(Lifecycle.ACTIVE),
        ).body()
    }

    @Test
    fun `parallel major lines accept backports and reject equal precedence with different build metadata`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-backport", UserRole.ADMIN)
        val contract = createContract(admin, "lines-backport")
        admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("2.0.0", ContractFixtures.openApi))
        assertEquals(
            HttpStatusCode.Created,
            admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("1.9.1", ContractFixtures.openApi)).status,
        )
        assertEquals(
            HttpStatusCode.Created,
            admin.postJson(
                "/api/v1/contracts/${contract.id}/versions",
                VersionCreateRequest("1.10.0+one", ContractFixtures.openApi),
            ).status,
        )
        assertEquals(
            HttpStatusCode.Conflict,
            admin.postJson(
                "/api/v1/contracts/${contract.id}/versions",
                VersionCreateRequest("1.10.0+two", ContractFixtures.openApi),
            ).status,
        )
        val temporary = admin.postJson(
            "/api/v1/contracts/${contract.id}/versions",
            VersionCreateRequest("3.0.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${contract.id}/versions/${temporary.id}").status)
        admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("0.1.0", ContractFixtures.openApi))
        val lines = admin.get("/api/v1/contracts/${contract.id}/release-lines").body<ReleaseLinePageResponse>()
        assertEquals(listOf(3, 2, 1, 0), lines.items.map { it.major })
        assertEquals(4, lines.items.sumOf { it.versionCount })
        assertEquals(0, lines.items.single { it.major == 3 }.versionCount, "an empty line retains its policy row")
        val firstLine = admin.get("/api/v1/contracts/${contract.id}/release-lines?sort=major&pageSize=1")
            .body<ReleaseLinePageResponse>().items.single()
        assertEquals(0, firstLine.major)
        assertEquals(1, admin.get("/api/v1/contracts/${contract.id}/versions?major=0").body<VersionPageResponse>().total)
        val majorOne = admin.get("/api/v1/contracts/${contract.id}/versions?major=1").body<VersionPageResponse>()
        assertEquals(listOf("1.10.0+one", "1.9.1"), majorOne.items.map { it.version })
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/${contract.id}/versions?major=1&major=2").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/${contract.id}/versions?major=4294967295").status)
        assertEquals(HttpStatusCode.BadRequest, admin.get("/api/v1/contracts/${contract.id}/release-lines?sort=version").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/contracts/${contract.id}/release-lines/99").status)
        assertEquals(
            HttpStatusCode.NotFound,
            admin.putJson(
                "/api/v1/contracts/${contract.id}/release-lines/99",
                ReleaseLineUpdateRequest(SupportStatus.UNSPECIFIED),
            ).status,
        )

        coroutineScope {
            val first = async {
                admin.postJson(
                    "/api/v1/contracts/${contract.id}/versions",
                    VersionCreateRequest("4.0.0+one", ContractFixtures.openApi),
                ).status
            }
            val second = async {
                admin.postJson(
                    "/api/v1/contracts/${contract.id}/versions",
                    VersionCreateRequest("4.0.0+two", ContractFixtures.openApi),
                ).status
            }
            assertEquals(setOf(HttpStatusCode.Created, HttpStatusCode.Conflict), setOf(first.await(), second.await()))
        }
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${contract.id}").status)
        assertEquals(HttpStatusCode.NotFound, admin.get("/api/v1/contracts/${contract.id}/release-lines").status)
    }

    @Test
    fun `support policy replacement is idempotent and recommendations obey line lifecycle`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-policy", UserRole.ADMIN)
        val contract = createContract(admin, "lines-policy")
        val v100 = activate(admin, contract, "1.0.0")
        val v110 = activate(admin, contract, "1.1.0")
        val follower = seededClient("lines-policy-follower", UserRole.USER)
        follower.put("/api/v1/contracts/${contract.id}/subscription")
        val path = "/api/v1/contracts/${contract.id}/release-lines/1"
        val request = ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, "2030-12-31", "Security fixes", v100.id)
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, "+10000-01-01")).status,
        )
        withAuditCapture { capture ->
            assertEquals(HttpStatusCode.NoContent, admin.putJson(path, request).status)
            capture.awaitEvent {
                it.message == "contract.release_line_updated" && it.hasKeyValue("major", 1)
            }
        }
        val firstNotifications = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
        val policyNotification = firstNotifications.single { it.type == NotificationType.RELEASE_LINE_UPDATED }
        assertEquals("1", policyNotification.params["major"])
        assertEquals("SUPPORTED", policyNotification.params["supportStatus"])
        assertEquals(HttpStatusCode.NoContent, admin.putJson(path, request).status)
        assertEquals(
            firstNotifications.size,
            follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items.size,
            "an idempotent replacement emits no second notification",
        )
        val line = admin.get(path).body<ReleaseLineResponse>()
        assertEquals(v110.id, line.latestVersion?.id)
        assertEquals(v100.id, line.recommendedVersion?.id)
        val events = admin.get("/api/v1/contracts/${contract.id}/events").body<ContractEventPageResponse>()
        assertEquals(1, events.items.count { it.type.name == "RELEASE_LINE_UPDATED" })

        val other = createContract(admin, "lines-policy-other")
        val otherVersion = activate(admin, other, "1.0.0")
        val majorTwo = activate(admin, contract, "2.0.0")
        val prerelease = activate(admin, contract, "1.2.0-rc.1")
        val draft = admin.postJson(
            "/api/v1/contracts/${contract.id}/versions",
            VersionCreateRequest("1.3.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        for (invalid in listOf(otherVersion.id, majorTwo.id, prerelease.id, draft.id)) {
            assertEquals(
                HttpStatusCode.BadRequest,
                admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, recommendedVersionId = invalid)).status,
            )
        }

        admin.postJson(
            "/api/v1/contracts/${contract.id}/versions/${v100.id}/transition",
            TransitionRequest(Lifecycle.DEPRECATED),
        )
        val automatic = admin.get(path).body<ReleaseLineResponse>()
        assertNull(automatic.recommendedVersionId)
        assertEquals(v110.id, automatic.recommendedVersion?.id)
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.END_OF_LIFE, recommendedVersionId = v110.id)).status,
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.END_OF_LIFE)).status)
        assertNull(admin.get(path).body<ReleaseLineResponse>().recommendedVersion)
    }

    @Test
    fun `release line write authorizes before decoding the body`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-auth-admin", UserRole.ADMIN)
        val contract = createContract(admin, "lines-auth")
        admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi))
        val outsider = seededClient("lines-auth-user", UserRole.USER)
        val response = outsider.put("/api/v1/contracts/${contract.id}/release-lines/1") {
            headers.append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("not-json")
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(HttpStatusCode.Forbidden.value, response.body<ProblemDetail>().status)
    }
}
