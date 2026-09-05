package ch.nokillswit

import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractResponse
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.ImportItem
import ch.nokillswit.contracts.ImportRequest
import ch.nokillswit.contracts.ImportResponse
import ch.nokillswit.contracts.ImportStatus
import ch.nokillswit.contracts.VersionPageResponse
import ch.nokillswit.users.UserRole
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Import and its dry run: report & skip, the statuses, the waiver, and dry-run/real-run parity. */
class ContractImportTest {

    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `a mixed batch classifies every row and the dry run predicts the same statuses`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("imp", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("imp")
        val teamId = TestTeams.seed(name("t"))
        val existing = admin.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("existing"), null, ownerTeamId = teamId),
        ).body<ContractResponse>()
        val newName = name("new")
        val items = listOf(
            // CREATED
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                newName,
                "d",
                ownerTeamId = teamId,
                version = "1.0.0",
                content = ContractFixtures.openApi,
            ),
            // VERSION_ADDED
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                existing.name,
                null,
                version = "1.0.0",
                content = ContractFixtures.openApi,
            ),
            // CREATED_WITH_FINDINGS
            ImportItem(
                systemId,
                ContractType.ODCS,
                name("odcs"),
                null,
                ownerTeamId = teamId,
                version = "1.0.0",
                content = ContractFixtures.odcsBroken,
            ),
            // INVALID: type mismatch
            ImportItem(
                systemId,
                ContractType.ASYNCAPI,
                existing.name,
                null,
                version = "2.0.0",
                content = ContractFixtures.asyncApi3,
            ),
            // INVALID: below highest (after row 1 lands)
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                existing.name,
                null,
                version = "0.9.0",
                content = ContractFixtures.openApi,
            ),
            // INVALID: hard
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                name("bad"),
                null,
                ownerTeamId = teamId,
                version = "1.0.0",
                content = "openapi: [oops",
            ),
            // INVALID: no owner
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                name("noowner"),
                null,
                version = "1.0.0",
                content = ContractFixtures.openApi,
            ),
            // CONFLICT: duplicate of row 1
            ImportItem(
                systemId,
                ContractType.OPENAPI,
                existing.name,
                null,
                version = "1.0.0",
                content = ContractFixtures.openApi,
            ),
        )
        val dry = admin.postJson("/api/v1/contracts/import/check", ImportRequest(items)).body<ImportResponse>().results
        val expectedDry = listOf(
            ImportStatus.CREATED, ImportStatus.VERSION_ADDED, ImportStatus.CREATED_WITH_FINDINGS, ImportStatus.INVALID,
            ImportStatus.VERSION_ADDED, ImportStatus.INVALID, ImportStatus.INVALID, ImportStatus.VERSION_ADDED,
        )
        assertEquals(expectedDry, dry.map { it.status }, "the dry run cannot see rows the real run stores first: rows 5 and 8 read as adds")
        assertTrue(dry.all { it.versionId == null }, "predictions store nothing")
        assertEquals(
            0,
            admin.get("/api/v1/contracts?systemId=$systemId&q=${newName}").body<ch.nokillswit.contracts.ContractPageResponse>().total,
        )

        val real = admin.postJson("/api/v1/contracts/import", ImportRequest(items)).body<ImportResponse>().results
        assertEquals(
            listOf(
                ImportStatus.CREATED, ImportStatus.VERSION_ADDED, ImportStatus.CREATED_WITH_FINDINGS, ImportStatus.INVALID,
                ImportStatus.INVALID, ImportStatus.INVALID, ImportStatus.INVALID, ImportStatus.CONFLICT,
            ),
            real.map { it.status },
        )
        assertNotNull(real[0].contractId); assertNotNull(real[0].versionId)
        assertEquals(existing.id, real[1].contractId)
        assertTrue(real[2].errors > 0); assertTrue(real[2].message!!.contains("ODCS_SCHEMA"))
        assertTrue(real[3].message!!.contains("OPENAPI"))
        assertTrue(real[4].message!!.contains("greater than"))
        assertNull(real[7].versionId)
        assertEquals(1, admin.get("/api/v1/contracts/${existing.id}/versions").body<VersionPageResponse>().total)
    }

    @Test
    fun `a stranger adding a version or claiming a foreign owner is FORBIDDEN - the batch cap is 400`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("impauth", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("impauth")
        val teamId = TestTeams.seed(name("t"))
        val existing = admin.postJson(
            "/api/v1/contracts",
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("e"), null, ownerTeamId = teamId),
        ).body<ContractResponse>()
        val stranger = seededClient("impstranger")
        val rows = stranger.postJson(
            "/api/v1/contracts/import",
            ImportRequest(
                listOf(
                    ImportItem(systemId, ContractType.OPENAPI, existing.name, null, version = "1.0.0", content = ContractFixtures.openApi),
                    ImportItem(
                        systemId,
                        ContractType.OPENAPI,
                        name("foreign"),
                        null,
                        ownerTeamId = teamId,
                        version = "1.0.0",
                        content = ContractFixtures.openApi,
                    ),
                ),
            ),
        ).body<ImportResponse>().results
        assertEquals(listOf(ImportStatus.FORBIDDEN, ImportStatus.FORBIDDEN), rows.map { it.status })
        val tooMany = (1..21).map {
            ImportItem(
                systemId, ContractType.OPENAPI, name("n$it"), null,
                ownerTeamId = teamId, version = "1.0.0", content = ContractFixtures.openApi,
            )
        }
        assertEquals(HttpStatusCode.BadRequest, admin.postJson("/api/v1/contracts/import", ImportRequest(tooMany)).status)
    }

    @Test
    fun `stored rows audit and mint IMPORTED events`() = testApplication {
        usePostgresTestcontainer()
        val admin = seededClient("impaudit", UserRole.ADMIN)
        val systemId = TestContracts.seedSystem("impaudit")
        val teamId = TestTeams.seed(name("t"))
        withAuditCapture { capture ->
            val rows = admin.postJson(
                "/api/v1/contracts/import",
                ImportRequest(
                    listOf(
                        ImportItem(
                            systemId,
                            ContractType.ODCS,
                            name("o"),
                            null,
                            ownerTeamId = teamId,
                            version = "3.0.0",
                            content = ContractFixtures.odcs,
                        ),
                    ),
                ),
            ).body<ImportResponse>().results
            assertEquals(ImportStatus.CREATED, rows.single().status)
            assertNotNull(capture.awaitEvent { it.message == "contract.imported" && it.hasKeyValue("version", "3.0.0") })
            val events = admin.get(
                "/api/v1/contracts/${rows.single().contractId}/events",
            ).body<ch.nokillswit.contracts.ContractEventPageResponse>()
            assertEquals("IMPORTED", events.items.first().type.name)
        }
    }
}
