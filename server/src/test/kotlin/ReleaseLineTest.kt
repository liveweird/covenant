package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractEventPageResponse
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Lifecycle
import ch.nokillswit.contracts.ReleaseLinePageResponse
import ch.nokillswit.contracts.ReleaseLineResponse
import ch.nokillswit.contracts.ReleaseLineReminderService
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    @Test
    fun `lifecycle plan validates dates and replacement while deleted targets remain readable`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-plan", UserRole.ADMIN)
        val source = createContract(admin, "lines-plan-source")
        admin.postJson("/api/v1/contracts/${source.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi))
        admin.postJson("/api/v1/contracts/${source.id}/versions", VersionCreateRequest("2.0.0", ContractFixtures.openApi))
        val path = "/api/v1/contracts/${source.id}/release-lines/1"

        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(
                path,
                ReleaseLineUpdateRequest(
                    SupportStatus.SUPPORTED,
                    supportEndsOn = "2030-12-31",
                    deprecatesOn = "2031-01-01",
                ),
            ).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, replacementMajor = 2)).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, replacementContractId = source.id)).status,
        )
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(
                path,
                ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, replacementContractId = source.id, replacementMajor = 2),
            ).status,
            "a different major of the same contract is a valid replacement",
        )

        val target = createContract(admin, "lines-plan-target")
        val targetVersion = admin.postJson(
            "/api/v1/contracts/${target.id}/versions",
            VersionCreateRequest("3.0.0", ContractFixtures.openApi),
        ).body<VersionResponse>()
        val guide = "  Move consumers in two steps.  "
        val request = ReleaseLineUpdateRequest(
            supportStatus = SupportStatus.MAINTENANCE,
            supportEndsOn = "2030-12-31",
            deprecatesOn = "2030-06-01",
            replacementContractId = target.id,
            replacementMajor = 3,
            migrationGuide = guide,
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson(path, request).status)
        val planned = admin.get(path).body<ReleaseLineResponse>()
        assertEquals("2030-06-01", planned.deprecatesOn)
        assertEquals("Move consumers in two steps.", planned.migrationGuide)
        assertEquals(target.name, planned.replacement?.contractName)
        assertTrue(planned.replacement?.available == true)

        val events = admin.get("/api/v1/contracts/${source.id}/events").body<ContractEventPageResponse>()
        val event = events.items.first { it.type.name == "RELEASE_LINE_UPDATED" }
        assertEquals("2030-06-01", event.params["deprecatesOn"])
        assertFalse("migrationGuide" in event.params)

        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${target.id}/versions/${targetVersion.id}").status)
        assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/contracts/${target.id}").status)
        val unavailable = admin.get(path).body<ReleaseLineResponse>().replacement!!
        assertEquals(target.id, unavailable.contractId)
        assertEquals(3, unavailable.major)
        assertFalse(unavailable.available)
        assertEquals(
            HttpStatusCode.NoContent,
            admin.putJson(path, request.copy(supportStatus = SupportStatus.SUPPORTED)).status,
            "an unchanged deleted reference remains writable so the rest of the policy can be updated",
        )
        assertEquals(HttpStatusCode.NoContent, admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED)).status)
        val cleared = admin.get(path).body<ReleaseLineResponse>()
        assertNull(cleared.deprecatesOn)
        assertNull(cleared.replacement)
        assertNull(cleared.migrationGuide)
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, replacementContractId = target.id)).status,
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, replacementContractId = UInt.MAX_VALUE)).status,
        )
    }

    @Test
    fun `scheduled reminders choose the current urgency bucket and deduplicate per recipient`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-reminders", UserRole.ADMIN)
        val contract = createContract(admin, "lines-reminders")
        admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi))
        val follower = seededClient("lines-reminders-follower", UserRole.USER)
        follower.put("/api/v1/contracts/${contract.id}/subscription")
        val path = "/api/v1/contracts/${contract.id}/release-lines/1"
        admin.putJson(
            path,
            ReleaseLineUpdateRequest(
                SupportStatus.SUPPORTED,
                supportEndsOn = "2030-01-01",
                deprecatesOn = "2030-01-01",
            ),
        )

        suspend fun scanAt(instant: String) {
            ReleaseLineReminderService(
                sharedDatabaseForTests(),
                TestNotifications.service,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC),
            ).scan()
        }
        coroutineScope {
            val first = async { scanAt("2029-12-10T23:59:59Z") }
            val second = async { scanAt("2029-12-10T23:59:59Z") }
            first.await()
            second.await()
        }
        scanAt("2029-12-26T00:00:00Z")
        scanAt("2030-01-01T12:00:00Z")
        val lateFollower = seededClient("lines-reminders-late-follower", UserRole.USER)
        lateFollower.put("/api/v1/contracts/${contract.id}/subscription")
        scanAt("2030-01-02T00:00:00Z")

        val reminders = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .filter { it.type == NotificationType.RELEASE_LINE_DEPRECATION_DUE }
        assertEquals(3, reminders.size)
        assertEquals(setOf("DUE_IN_30_DAYS", "DUE_IN_7_DAYS", "DUE_TODAY"), reminders.map { it.params["stage"] }.toSet())
        reminders.forEach {
            assertEquals(contract.name, it.params["contractName"])
            assertEquals("1", it.params["major"])
            assertEquals("2030-01-01", it.params["deadline"])
        }
        val supportReminders = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .filter { it.type == NotificationType.RELEASE_LINE_SUPPORT_END_DUE }
        assertEquals(3, supportReminders.size)
        assertEquals(setOf("DUE_IN_30_DAYS", "DUE_IN_7_DAYS", "DUE_TODAY"), supportReminders.map { it.params["stage"] }.toSet())
        val catchup = lateFollower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .single { it.type == NotificationType.RELEASE_LINE_DEPRECATION_DUE }
        assertEquals("OVERDUE", catchup.params["stage"])
    }

    @Test
    fun `reminder dedup survives deletion while changed and cleared dates behave independently`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-reminder-date", UserRole.ADMIN)
        val contract = createContract(admin, "lines-reminder-date")
        admin.postJson("/api/v1/contracts/${contract.id}/versions", VersionCreateRequest("1.0.0", ContractFixtures.openApi))
        val follower = seededClient("lines-reminder-date-follower", UserRole.USER)
        follower.put("/api/v1/contracts/${contract.id}/subscription")
        val path = "/api/v1/contracts/${contract.id}/release-lines/1"
        val service = ReleaseLineReminderService(
            sharedDatabaseForTests(),
            TestNotifications.service,
            Clock.fixed(Instant.parse("2029-12-10T00:00:00Z"), ZoneOffset.UTC),
        )

        admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, deprecatesOn = "2030-01-01"))
        service.scan()
        val first = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .single { it.type == NotificationType.RELEASE_LINE_DEPRECATION_DUE }
        assertEquals(HttpStatusCode.NoContent, follower.delete("/api/v1/notifications/${first.id}").status)

        admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, deprecatesOn = "2030-01-02"))
        service.scan()
        val changed = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .single { it.type == NotificationType.RELEASE_LINE_DEPRECATION_DUE }
        assertEquals("2030-01-02", changed.params["deadline"])
        assertEquals(HttpStatusCode.NoContent, follower.delete("/api/v1/notifications/${changed.id}").status)

        admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED))
        service.scan()
        admin.putJson(path, ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, deprecatesOn = "2030-01-01"))
        service.scan()
        val active = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .filter { it.type == NotificationType.RELEASE_LINE_DEPRECATION_DUE }
        assertEquals(emptyList(), active, "soft-deleting a reminder does not release its durable dedup key")
    }

    @Test
    fun `reminders suppress end-of-life empty and deleted source lines`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("lines-reminder-suppress", UserRole.ADMIN)
        val follower = seededClient("lines-reminder-suppress-follower", UserRole.USER)

        suspend fun fixture(prefix: String): Pair<ContractResponse, VersionResponse> {
            val contract = createContract(admin, prefix)
            val version = admin.postJson(
                "/api/v1/contracts/${contract.id}/versions",
                VersionCreateRequest("1.0.0", ContractFixtures.openApi),
            ).body<VersionResponse>()
            follower.put("/api/v1/contracts/${contract.id}/subscription")
            return contract to version
        }

        val (eol, _) = fixture("lines-reminder-eol")
        admin.putJson(
            "/api/v1/contracts/${eol.id}/release-lines/1",
            ReleaseLineUpdateRequest(SupportStatus.END_OF_LIFE, deprecatesOn = "2030-01-01"),
        )
        val (empty, emptyVersion) = fixture("lines-reminder-empty")
        admin.putJson(
            "/api/v1/contracts/${empty.id}/release-lines/1",
            ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, deprecatesOn = "2030-01-01"),
        )
        admin.delete("/api/v1/contracts/${empty.id}/versions/${emptyVersion.id}")
        val (deleted, _) = fixture("lines-reminder-deleted")
        admin.putJson(
            "/api/v1/contracts/${deleted.id}/release-lines/1",
            ReleaseLineUpdateRequest(SupportStatus.SUPPORTED, supportEndsOn = "2030-01-01"),
        )
        admin.delete("/api/v1/contracts/${deleted.id}")

        ReleaseLineReminderService(
            sharedDatabaseForTests(),
            TestNotifications.service,
            Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
        ).scan()
        val reminders = follower.get("/api/v1/notifications?pageSize=100").body<NotificationPageResponse>().items
            .filter { it.type in setOf(NotificationType.RELEASE_LINE_DEPRECATION_DUE, NotificationType.RELEASE_LINE_SUPPORT_END_DUE) }
        assertEquals(emptyList(), reminders)
    }
}
