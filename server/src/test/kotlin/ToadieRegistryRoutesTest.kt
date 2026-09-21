package ch.nokillswit

import ch.nokillswit.contracts.ContractService
import ch.nokillswit.domains.DomainRequest
import ch.nokillswit.domains.DomainResponse
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.systems.SystemRequest
import ch.nokillswit.systems.SystemResponse
import ch.nokillswit.teams.TeamResponse
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.toadie.*
import ch.nokillswit.users.UserRole
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ToadieRegistryRoutesTest {
    private fun service() = ToadieService(
        sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
        ContractService(sharedDatabaseForTests(), TestTeams.service),
    )

    private fun request() = ToadieConnectionRequest(
        name = "registry-routes-${System.nanoTime()}", baseUrl = "http://registry.internal",
        browserUrl = "http://registry.internal", apiKey = "registry-key", enabled = true,
        refreshIntervalMinutes = 60, registryMapping = ToadieRegistryMapping(flattenDomains = true),
    )

    private fun snapshot() = ToadieSnapshot(
        listOf(
            ToadieEntitySnapshot("1", "domain", "commerce", "Domain-${System.nanoTime()}", emptyList(), emptyMap(), 1),
            ToadieEntitySnapshot(
                "2", "system", "commerce", "System-${System.nanoTime()}", emptyList(),
                mapOf("domain" to listOf("commerce")), 1,
            ),
            ToadieEntitySnapshot("3", "_team", "retail", "Team-${System.nanoTime()}", emptyList(), emptyMap(), 1),
        ), "system", System.currentTimeMillis(), 12,
    )

    @Test
    fun `registry routes guard before decoding and expose declared absence and cache conflicts`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("registry-route-admin", UserRole.ADMIN)
        val reader = seededClient("registry-route-reader", UserRole.USER)
        val service = service()
        val id = service.create(request())
        try {
            val valid = ToadieRegistryPreviewRequest(ToadieRegistryKind.DOMAIN, listOf(ToadieRegistrySelection("1")))
            val apply = ToadieRegistryApplyRequest(valid.kind, valid.items, "0".repeat(64))
            val base = "/api/v1/toadie-connections/$id"
            assertEquals(HttpStatusCode.Forbidden, reader.get("$base/registry-candidates?kind=invalid&sort=invalid").status)
            for (path in listOf("registry-sync/preview", "registry-sync")) {
                assertEquals(HttpStatusCode.Forbidden, reader.post("$base/$path") {
                    contentType(ContentType.Application.Json)
                    setBody("{")
                }.status)
            }
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base/registry-candidates?kind=domain").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base/registry-candidates?kind=DOMAIN&kind=TEAM").status)
            assertEquals(HttpStatusCode.BadRequest, admin.get("$base/registry-candidates?kind=DOMAIN&sort=unknown").status)
            for (items in listOf(
                emptyList(),
                listOf(ToadieRegistrySelection("01")),
                listOf(ToadieRegistrySelection("1"), ToadieRegistrySelection("1")),
                listOf(ToadieRegistrySelection("1", fallbackDomainId = 1u)),
            )) {
                assertEquals(HttpStatusCode.BadRequest, admin.postJson(
                    "$base/registry-sync/preview", valid.copy(items = items),
                ).status)
                assertEquals(HttpStatusCode.BadRequest, admin.postJson(
                    "$base/registry-sync", apply.copy(items = items),
                ).status)
            }
            assertEquals(HttpStatusCode.BadRequest, admin.postJson(
                "$base/registry-sync", apply.copy(expectedPlanToken = "stale"),
            ).status)
            for (kind in ToadieRegistryKind.entries) {
                assertEquals(HttpStatusCode.Conflict, admin.get("$base/registry-candidates?kind=$kind").status)
            }
            assertEquals(HttpStatusCode.Conflict, admin.postJson("$base/registry-sync/preview", valid).status)
            assertEquals(HttpStatusCode.Conflict, admin.postJson("$base/registry-sync", apply).status)
            val absent = "/api/v1/toadie-connections/2147483647"
            assertEquals(HttpStatusCode.NotFound, admin.get("$absent/registry-candidates?kind=DOMAIN").status)
            assertEquals(HttpStatusCode.NotFound, admin.postJson("$absent/registry-sync/preview", valid).status)
            assertEquals(HttpStatusCode.NotFound, admin.postJson("$absent/registry-sync", apply).status)
            for (kind in listOf("domains", "systems", "teams")) {
                val path = "/api/v1/$kind/2147483647/toadie-source"
                assertEquals(HttpStatusCode.Forbidden, reader.delete(path).status)
                assertEquals(HttpStatusCode.NotFound, admin.delete(path).status)
            }
        } finally { service.delete(id) }
    }

    @Test
    fun `preview apply and detach preserve local IDs and enforce source ownership through HTTP`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("registry-apply-admin", UserRole.ADMIN)
        val reader = seededClient("registry-apply-reader", UserRole.USER)
        val service = service()
        val id = service.create(request())
        val localRows = mutableListOf<Pair<String, UInt>>()
        try {
            val claim = assertNotNull(service.claimRefresh(id, force = false).second)
            assertTrue(service.publish(claim, snapshot()))
            val base = "/api/v1/toadie-connections/$id"
            val candidates = admin.get("$base/registry-candidates?kind=SYSTEM&pageSize=1").body<ToadieRegistryCandidatePageResponse>()
            assertEquals(1, candidates.items.size)
            assertEquals("1", candidates.items.single().parentEntityId)
            assertEquals(1L, candidates.total)
            for ((kind, entityId, path) in listOf(
                Triple(ToadieRegistryKind.DOMAIN, "1", "domains"),
                Triple(ToadieRegistryKind.SYSTEM, "2", "systems"),
                Triple(ToadieRegistryKind.TEAM, "3", "teams"),
            )) {
                val selection = ToadieRegistryPreviewRequest(kind, listOf(ToadieRegistrySelection(entityId)))
                val previewResponse = admin.postJson("$base/registry-sync/preview", selection)
                assertEquals(HttpStatusCode.OK, previewResponse.status)
                val preview = previewResponse.body<ToadieRegistryPreviewResponse>()
                assertTrue(preview.canApply)
                val body = ToadieRegistryApplyRequest(kind, selection.items, preview.planToken)
                val appliedResponse = admin.postJson("$base/registry-sync", body)
                assertEquals(HttpStatusCode.OK, appliedResponse.status)
                val localId = appliedResponse.body<ToadieRegistryApplyResponse>().items.single().localId
                localRows.add(path to localId)
                assertEquals(HttpStatusCode.Conflict, admin.postJson("$base/registry-sync", body).status)
                assertSourceLocked(admin, reader, path, localId)
            }
            for ((path, localId) in localRows.reversed()) {
                assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/$path/$localId/toadie-source").status)
                assertEquals(HttpStatusCode.NoContent, admin.delete("/api/v1/$path/$localId/toadie-source").status)
            }
            val teamId = localRows.single { it.first == "teams" }.second
            assertEquals(HttpStatusCode.NoContent, admin.putJson(
                "/api/v1/teams/$teamId", TeamUpdateRequest("local-team-${System.nanoTime()}", "Local description"),
            ).status)
            assertEquals(null, reader.get("/api/v1/teams/$teamId").body<TeamResponse>().source)
            val bad = ToadieRegistryPreviewRequest(ToadieRegistryKind.DOMAIN, listOf(ToadieRegistrySelection("999999")))
            val preview = admin.postJson("$base/registry-sync/preview", bad).body<ToadieRegistryPreviewResponse>()
            assertFalse(preview.canApply)
            assertTrue("SOURCE_MISSING" in preview.items.single().issues)
        } finally {
            localRows.reversed().forEach { (path, localId) -> admin.delete("/api/v1/$path/$localId") }
            service.delete(id)
        }
    }

    private suspend fun assertSourceLocked(admin: HttpClient, reader: HttpClient, path: String, id: UInt) {
        val url = "/api/v1/$path/$id"
        when (path) {
            "domains" -> {
                val record = reader.get(url).body<DomainResponse>()
                assertNotNull(record.source)
                assertEquals(HttpStatusCode.Conflict, admin.putJson(url, DomainRequest("changed-${System.nanoTime()}")).status)
                assertEquals(HttpStatusCode.NoContent, admin.putJson(url, DomainRequest(record.name, "Local description")).status)
            }
            "systems" -> {
                val record = reader.get(url).body<SystemResponse>()
                assertNotNull(record.source)
                val update = SystemRequest(record.domainId, "changed-${System.nanoTime()}")
                assertEquals(HttpStatusCode.Conflict, admin.putJson(url, update).status)
            }
            "teams" -> {
                val record = reader.get(url).body<TeamResponse>()
                assertNotNull(record.source)
                assertEquals(HttpStatusCode.Conflict, admin.putJson(url, TeamUpdateRequest("changed-${System.nanoTime()}")).status)
            }
        }
    }
}
