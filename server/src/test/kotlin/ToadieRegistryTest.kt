package ch.nokillswit

import ch.nokillswit.authz.ConflictException
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.domains.DomainRequest
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.teams.TeamCreateRequest
import ch.nokillswit.teams.TeamUpdateRequest
import ch.nokillswit.toadie.*
import io.ktor.server.testing.testApplication
import kotlin.test.*

class ToadieRegistryTest {
    private fun service() = ToadieService(
        sharedDatabaseForTests(), FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY),
        ContractService(sharedDatabaseForTests(), TestTeams.service),
    )

    private fun request(name: String, descriptions: Boolean = true) = ToadieConnectionRequest(
        name = name,
        baseUrl = "http://toadie-registry.internal/integration/graphql",
        browserUrl = "http://toadie-registry.internal",
        apiKey = "registry-secret",
        enabled = true,
        refreshIntervalMinutes = 60,
        registryMapping = ToadieRegistryMapping(
            flattenDomains = true,
            domainDescriptionProperty = "summary".takeIf { descriptions },
            systemDescriptionProperty = "summary".takeIf { descriptions },
            teamDescriptionProperty = "summary".takeIf { descriptions },
        ),
    )

    private fun snapshot(
        revision: Long,
        domainTitle: String = "Remote Domain",
        systemTitle: String = "Remote System",
        teamTitle: String = "Remote Team",
        includeTeam: Boolean = true,
        systemDomain: String? = "remote-domain",
    ) = ToadieSnapshot(
        entities = buildList {
            add(
                ToadieEntitySnapshot(
                    "101", "domain", "remote-domain", domainTitle, emptyList(),
                    mapOf("parent_domain" to emptyList()), 1,
                    registryDescription = "Domain description",
                ),
            )
            add(
                ToadieEntitySnapshot(
                    "102", "system", "remote-system", systemTitle, emptyList(),
                    mapOf("domain" to listOfNotNull(systemDomain)), 1,
                    registryDescription = "System description",
                ),
            )
            if (includeTeam) add(
                ToadieEntitySnapshot(
                    "103", "_team", "remote-team", teamTitle, emptyList(),
                    mapOf("parent" to emptyList()), 1,
                    registryDescription = "Team description",
                ),
            )
        },
        systemBlueprint = "system",
        fetchedAt = System.currentTimeMillis(),
        revision = revision,
    )

    @Test
    fun `selected imports and links apply source metadata while preserving team rosters`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val connectionId = service.create(request("registry-${System.nanoTime()}"))
        val marker = System.nanoTime()
        assertTrue(
            service.publish(
                service.claimRefresh(connectionId, false).second!!,
                snapshot(1, "Remote Domain $marker", "Remote System $marker", "Remote Team $marker"),
            ),
        )

        val domain = apply(service, connectionId, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101"))
        val domainRow = assertNotNull(TestDomains.service.read(domain.localId))
        assertEquals("Remote Domain $marker", domainRow.name)
        assertEquals("Domain description", domainRow.description)
        assertEquals(ToadieRegistrySourceStatus.AVAILABLE, domainRow.source?.status)

        val system = apply(service, connectionId, ToadieRegistryKind.SYSTEM, ToadieRegistrySelection("102"))
        val systemRow = assertNotNull(TestSystems.service.read(system.localId))
        assertEquals(domain.localId, systemRow.domainId)
        assertEquals("System description", systemRow.description)

        val memberId = TestUsers.seed(uniqueEmail("registry-roster"), "password123")
        val localTeam = TestTeams.service.create(TeamCreateRequest("Local Team", memberIds = listOf(memberId)))
        val team = apply(
            service, connectionId, ToadieRegistryKind.TEAM,
            ToadieRegistrySelection("103", localId = localTeam),
        )
        assertEquals(localTeam, team.localId)
        val teamRow = assertNotNull(TestTeams.service.read(localTeam))
        assertEquals("Remote Team $marker", teamRow.name)
        assertEquals(listOf(memberId), teamRow.members.map { it.userId })

        assertFailsWith<ConflictException> {
            TestTeams.service.update(localTeam, TeamUpdateRequest("Edited remotely owned name", teamRow.description))
        }
        assertTrue(service.detachRegistrySource(ToadieRegistryKind.TEAM, localTeam))
        assertTrue(service.detachRegistrySource(ToadieRegistryKind.TEAM, localTeam))
        assertEquals(1, TestTeams.service.update(localTeam, TeamUpdateRequest("Local Again", teamRow.description)))
    }

    @Test
    fun `refresh reconciles metadata placement missing sources and conflict recovery without inserting rows`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val connectionId = service.create(request("reconcile-${System.nanoTime()}"))
        val marker = System.nanoTime()
        assertTrue(
            service.publish(
                service.claimRefresh(connectionId, false).second!!,
                snapshot(10, "Initial Domain $marker", "Initial System $marker", "Initial Team $marker"),
            ),
        )
        val domain = apply(service, connectionId, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101"))
        val fallback = TestDomains.seed("Fallback ${System.nanoTime()}")
        val system = apply(
            service, connectionId, ToadieRegistryKind.SYSTEM,
            ToadieRegistrySelection("102", fallbackDomainId = fallback),
        )
        val team = apply(service, connectionId, ToadieRegistryKind.TEAM, ToadieRegistrySelection("103"))

        withAuditCapture { capture ->
            assertTrue(
                service.publish(
                    service.claimRefresh(connectionId, false).second!!,
                    snapshot(11, "Renamed Domain $marker", "Renamed System $marker", includeTeam = false),
                ),
            )
            assertTrue(capture.events.any {
                it.message == "toadie_registry.reconciled" && it.hasKeyValue("connectionId", connectionId.toLong())
            })
        }
        assertEquals("Renamed Domain $marker", TestDomains.service.read(domain.localId)?.name)
        assertEquals("Renamed System $marker", TestSystems.service.read(system.localId)?.name)
        assertEquals(domain.localId, TestSystems.service.read(system.localId)?.domainId)
        val missingTeam = assertNotNull(TestTeams.service.read(team.localId))
        assertEquals("Initial Team $marker", missingTeam.name)
        assertEquals(ToadieRegistrySourceStatus.MISSING, missingTeam.source?.status)

        val blocker = TestDomains.seed("Recovered Domain $marker")
        assertTrue(
            service.publish(
                service.claimRefresh(connectionId, false).second!!,
                snapshot(12, domainTitle = "Recovered Domain $marker", includeTeam = false),
            ),
        )
        val conflicted = assertNotNull(TestDomains.service.read(domain.localId))
        assertEquals("Renamed Domain $marker", conflicted.name)
        assertEquals(ToadieRegistrySourceStatus.CONFLICT, conflicted.source?.status)
        TestDomains.service.update(blocker, DomainRequest("Unblocked ${System.nanoTime()}"))
        withAuditCapture { capture ->
            assertTrue(
                service.publish(
                    service.claimRefresh(connectionId, false).second!!,
                    ToadieUnchanged(12, System.currentTimeMillis()),
                ),
            )
            assertTrue(capture.events.any {
                it.message == "toadie_registry.reconciled" && it.hasKeyValue("connectionId", connectionId.toLong())
            })
        }
        val recovered = assertNotNull(TestDomains.service.read(domain.localId))
        assertEquals("Recovered Domain $marker", recovered.name)
        assertEquals(ToadieRegistrySourceStatus.AVAILABLE, recovered.source?.status)
        withAuditCapture { capture ->
            assertTrue(
                service.publish(
                    service.claimRefresh(connectionId, false).second!!,
                    ToadieUnchanged(12, System.currentTimeMillis()),
                ),
            )
            assertFalse(capture.events.any {
                it.message == "toadie_registry.reconciled" && it.hasKeyValue("connectionId", connectionId.toLong())
            })
        }
        val unchanged = assertNotNull(TestDomains.service.read(domain.localId))
        assertEquals(recovered.updatedAt, unchanged.updatedAt)
        assertEquals(recovered.source?.lastSyncedAt, unchanged.source?.lastSyncedAt)
    }

    @Test
    fun `adding a description mapping promotes sticky ownership on the next full refresh`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val name = "description-${System.nanoTime()}"
        val initialRequest = request(name, descriptions = false)
        val connectionId = service.create(initialRequest)
        val marker = System.nanoTime()
        val current = snapshot(40, domainTitle = "Description Domain $marker")
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, current))
        val imported = apply(service, connectionId, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101"))
        assertNull(TestDomains.service.read(imported.localId)?.description)

        assertEquals(1, service.update(connectionId, request(name, descriptions = true).copy(apiKey = null)))
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, current.copy(revision = 41)))
        val synchronized = assertNotNull(TestDomains.service.read(imported.localId))
        assertEquals("Domain description", synchronized.description)
        assertTrue(synchronized.source?.descriptionSynced == true)
        assertFailsWith<ConflictException> {
            TestDomains.service.update(imported.localId, DomainRequest(synchronized.name, "Local edit"))
        }
    }

    @Test
    fun `preview token guards local edits and connection scoped bindings with identical remote ids`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val firstConnection = service.create(request("first-${System.nanoTime()}"))
        val secondConnection = service.create(
            request("second-${System.nanoTime()}").copy(baseUrl = "http://second-toadie.internal/integration/graphql"),
        )
        val marker = System.nanoTime()
        assertTrue(service.publish(service.claimRefresh(firstConnection, false).second!!, snapshot(20, domainTitle = "First $marker")))
        assertTrue(service.publish(service.claimRefresh(secondConnection, false).second!!, snapshot(30, domainTitle = "Second $marker")))
        val local = TestDomains.seed("Local ${System.nanoTime()}")
        apply(service, firstConnection, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101", localId = local))

        val crossConnection = assertNotNull(
            service.previewRegistrySync(
                secondConnection,
                ToadieRegistryPreviewRequest(ToadieRegistryKind.DOMAIN, listOf(ToadieRegistrySelection("101", local))),
            ),
        )
        assertFalse(crossConnection.canApply)
        assertTrue("LOCAL_ALREADY_LINKED" in crossConnection.items.single().issues)

        val unlinked = TestDomains.seed("Preview Local ${System.nanoTime()}")
        val request = ToadieRegistryPreviewRequest(
            ToadieRegistryKind.DOMAIN,
            listOf(ToadieRegistrySelection("101", localId = unlinked)),
        )
        val preview = assertNotNull(service.previewRegistrySync(secondConnection, request))
        TestDomains.service.update(unlinked, DomainRequest("Edited after preview"))
        assertFailsWith<ConflictException> {
            service.applyRegistrySync(
                secondConnection,
                ToadieRegistryApplyRequest(request.kind, request.items, preview.planToken),
            )
        }
    }

    @Test
    fun `binding settings participate in tokens and invalid batches remain atomic`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val connectionId = service.create(request("token-${System.nanoTime()}"))
        val marker = System.nanoTime()
        val mapped = snapshot(
            50, "Token Domain $marker", "Token System $marker", "Token Team $marker",
        )
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, mapped))
        val fallback1 = TestDomains.seed("Fallback 1 $marker")
        val fallback2 = TestDomains.seed("Fallback 2 $marker")
        val fallback3 = TestDomains.seed("Fallback 3 $marker")
        val sourceDomain = apply(service, connectionId, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101"))
        val system = apply(
            service, connectionId, ToadieRegistryKind.SYSTEM,
            ToadieRegistrySelection("102", fallbackDomainId = fallback1),
        )
        val requestA = ToadieRegistryPreviewRequest(
            ToadieRegistryKind.SYSTEM,
            listOf(ToadieRegistrySelection("102", system.localId, fallback2)),
        )
        val requestB = requestA.copy(items = listOf(ToadieRegistrySelection("102", system.localId, fallback3)))
        val previewA = assertNotNull(service.previewRegistrySync(connectionId, requestA))
        val previewB = assertNotNull(service.previewRegistrySync(connectionId, requestB))
        assertNotNull(
            service.applyRegistrySync(
                connectionId,
                ToadieRegistryApplyRequest(requestB.kind, requestB.items, previewB.planToken),
            ),
        )
        assertEquals(sourceDomain.localId, TestSystems.service.read(system.localId)?.domainId)
        assertFailsWith<ConflictException> {
            service.applyRegistrySync(
                connectionId,
                ToadieRegistryApplyRequest(requestA.kind, requestA.items, previewA.planToken),
            )
        }

        val invalidBatch = ToadieRegistryPreviewRequest(
            ToadieRegistryKind.TEAM,
            listOf(ToadieRegistrySelection("103"), ToadieRegistrySelection("999")),
        )
        val invalidPreview = assertNotNull(service.previewRegistrySync(connectionId, invalidBatch))
        assertFalse(invalidPreview.canApply)
        assertFailsWith<ConflictException> {
            service.applyRegistrySync(
                connectionId,
                ToadieRegistryApplyRequest(invalidBatch.kind, invalidBatch.items, invalidPreview.planToken),
            )
        }
        val candidate = assertNotNull(
            service.registryCandidates(
                connectionId, ToadieRegistryKind.TEAM, null,
                ch.nokillswit.infra.paging.PageRequest(
                    1, 20, listOf(ch.nokillswit.infra.paging.SortField("id", descending = false)),
                ),
            ),
        ).items.single { it.entityId == "103" }
        assertNull(candidate.linkedLocalId)
    }

    @Test
    fun `mapped systems validate fallback ids and wrong-blueprint cached ids become missing`() = testApplication {
        usePostgresTestcontainer()
        val service = service()
        val connectionId = service.create(request("blueprint-${System.nanoTime()}"))
        val marker = System.nanoTime()
        val current = snapshot(60, "Blueprint Domain $marker", "Blueprint System $marker")
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, current))
        val domain = apply(service, connectionId, ToadieRegistryKind.DOMAIN, ToadieRegistrySelection("101"))
        val invalidFallback = assertNotNull(
            service.previewRegistrySync(
                connectionId,
                ToadieRegistryPreviewRequest(
                    ToadieRegistryKind.SYSTEM,
                    listOf(ToadieRegistrySelection("102", fallbackDomainId = UInt.MAX_VALUE)),
                ),
            ),
        )
        assertFalse(invalidFallback.canApply)
        assertTrue("DOMAIN_NOT_FOUND" in invalidFallback.items.single().issues)
        val system = apply(service, connectionId, ToadieRegistryKind.SYSTEM, ToadieRegistrySelection("102"))
        assertEquals(domain.localId, TestSystems.service.read(system.localId)?.domainId)

        val wrongKind = current.copy(
            entities = current.entities.map {
                if (it.id == "102") it.copy(blueprint = "api", title = "Wrong Kind") else it
            },
            revision = 61,
        )
        assertTrue(service.publish(service.claimRefresh(connectionId, false).second!!, wrongKind))
        val retained = assertNotNull(TestSystems.service.read(system.localId))
        assertEquals("Blueprint System $marker", retained.name)
        assertEquals(ToadieRegistrySourceStatus.MISSING, retained.source?.status)
    }

    private suspend fun apply(
        service: ToadieService,
        connectionId: UInt,
        kind: ToadieRegistryKind,
        selection: ToadieRegistrySelection,
    ): ToadieRegistryApplyItem {
        val request = ToadieRegistryPreviewRequest(kind, listOf(selection))
        val preview = assertNotNull(service.previewRegistrySync(connectionId, request))
        assertTrue(preview.canApply, preview.items.single().issues.joinToString())
        return assertNotNull(
            service.applyRegistrySync(
                connectionId,
                ToadieRegistryApplyRequest(kind, request.items, preview.planToken),
            ),
        ).items.single()
    }
}
