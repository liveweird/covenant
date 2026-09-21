package ch.nokillswit

import ch.nokillswit.contracts.*
import ch.nokillswit.contracts.checks.CheckerClientKey
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class SemVerSqlOrderTest {
    @Test
    fun `SQL version ordering matches SemVer across pages filters errors and exports`() = testApplication {
        configureApp()
        application { attributes.put(CheckerClientKey, TestChecker.lint()) }
        startApplication()
        val admin = seededClient("sql-semver", UserRole.ADMIN)
        val system = TestContracts.seedSystem("sql-semver")
        val team = TestTeams.seed("sql-semver-${System.nanoTime()}")
        val contract = admin.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(system, ContractType.OPENAPI, "sql-semver", ownerTeamId = team),
        ).body<ContractResponse>()
        val versions = listOf(
            "1.2.0-rc.2", "1.2.0-rc.10", "1.2.0", "1.2.0-alpha", "1.2.0-alpha.1",
            "1.2.0-alpha-1", "1.2.0-0", "1.2.0-9999999999999999999999999999999999999999",
            "1.2.0-10000000000000000000000000000000000000000", "1.2.0-Z", "1.2.0-a",
            "1.2.0-rc.1a", "1.2.0-rc.1", "1.2.0-rc.1.0", "1.1.9", "2.0.0-0",
        )
        for (version in versions) {
            val response = admin.postJson(
                "/api/v1/contracts/${contract.id}/versions",
                VersionCreateRequest(version, ContractFixtures.openApi),
            )
            assertEquals(HttpStatusCode.Created, response.status)
        }
        val expected = versions.sortedBy { SemVer.parse(it) }
        for (descending in listOf(false, true)) {
            val sort = if (descending) "-version" else "version"
            for (major in listOf<Int?>(null, 1)) {
                val selected = expected.filter { major == null || SemVer.parse(it).major == major }
                    .let { if (descending) it.reversed() else it }
                val actual = selected.indices.map { index ->
                    val filter = major?.let { "&major=$it" }.orEmpty()
                    admin.get("/api/v1/contracts/${contract.id}/versions?page=${index + 1}&pageSize=1&sort=$sort$filter")
                        .body<VersionPageResponse>().items.single().version
                }
                assertEquals(selected, actual, "sort=$sort major=$major")
            }
            val errorVersions = versions.indices.map { index ->
                admin.get("/api/v1/contracts/errors?systemId=$system&page=${index + 1}&pageSize=1&sort=$sort")
                    .body<ErrorPageResponse>().items.single().version.version
            }
            assertEquals(if (descending) expected.reversed() else expected, errorVersions)
        }
        val exported = admin.get("/api/v1/contracts/${contract.id}/export").body<ContractExportResponse>()
        assertEquals(expected.reversed(), exported.versions.map { it.version })
    }
}
