package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ConflictException
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.Ownership
import ch.nokillswit.domains.DomainService
import ch.nokillswit.environments.EnvironmentRequest
import ch.nokillswit.environments.EnvironmentService
import ch.nokillswit.infra.crypto.FieldCipher
import ch.nokillswit.systems.SystemRequest
import ch.nokillswit.systems.SystemService
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserRole
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.testing.testApplication
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Parent soft-delete and child/reference attachment serialize on the parent row in both orders. */
class ParentLockingTest {
    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    private suspend fun R2dbcTransaction.backendPid(): Int =
        exec("SELECT pg_backend_pid()") { row -> row.get(0, Int::class.javaObjectType) }!!
            .filterNotNull().single()

    private suspend fun waitUntilBlocked(database: R2dbcDatabase, pid: Int, expectedBlocker: Int) {
        withTimeout(10_000) {
            while (true) {
                val expectedIsBlocking = suspendTransaction(database) {
                    exec("SELECT $expectedBlocker = ANY(pg_blocking_pids($pid))") { row ->
                        row.get(0, Boolean::class.javaObjectType)
                    }!!.filterNotNull().single()
                }
                if (expectedIsBlocking) return@withTimeout
                yield()
            }
        }
    }

    private suspend fun releaseAfterBlocked(
        database: R2dbcDatabase,
        contenderPid: Int,
        holderPid: Int,
        release: CompletableDeferred<Unit>,
    ) {
        try {
            waitUntilBlocked(database, contenderPid, holderPid)
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `system create and move serialize with domain delete in both orders`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val domains = DomainService(database)
        val systems = SystemService(database, domains)

        coroutineScope {
            val attachDomain = TestDomains.seed(name("domain-attach"))
            val attached = CompletableDeferred<Int>()
            val releaseAttach = CompletableDeferred<Unit>()
            val creation = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    systems.create(SystemRequest(attachDomain, name("system")))
                    attached.complete(backendPid())
                    releaseAttach.await()
                }
            }
            val attachPid = attached.await()
            val deletePid = CompletableDeferred<Int>()
            val deletion = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        deletePid.complete(backendPid())
                        domains.delete(attachDomain)
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, deletePid.await(), attachPid, releaseAttach)
            creation.await()
            assertIs<ConflictException>(deletion.await())

            val deletedFirst = TestDomains.seed(name("domain-delete-first"))
            val deleted = CompletableDeferred<Int>()
            val releaseDelete = CompletableDeferred<Unit>()
            val heldDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    assertEquals(1, domains.delete(deletedFirst))
                    deleted.complete(backendPid())
                    releaseDelete.await()
                }
            }
            val deleteFirstPid = deleted.await()
            val createPid = CompletableDeferred<Int>()
            val rejectedCreate = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        createPid.complete(backendPid())
                        systems.create(SystemRequest(deletedFirst, name("rejected-system")))
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, createPid.await(), deleteFirstPid, releaseDelete)
            heldDelete.await()
            assertIs<BadRequestException>(rejectedCreate.await())

            val oldDomain = TestDomains.seed(name("old-domain"))
            val targetDomain = TestDomains.seed(name("target-domain"))
            val systemId = systems.create(SystemRequest(oldDomain, name("moving-system")))
            val moved = CompletableDeferred<Int>()
            val releaseMove = CompletableDeferred<Unit>()
            val move = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    systems.update(systemId, SystemRequest(targetDomain, name("moving-system")))
                    moved.complete(backendPid())
                    releaseMove.await()
                }
            }
            val moveHolderPid = moved.await()
            val targetDeletePid = CompletableDeferred<Int>()
            val targetDelete = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        targetDeletePid.complete(backendPid())
                        domains.delete(targetDomain)
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, targetDeletePid.await(), moveHolderPid, releaseMove)
            move.await()
            assertIs<ConflictException>(targetDelete.await())

            val deletedTarget = TestDomains.seed(name("deleted-target"))
            val targetDeleted = CompletableDeferred<Int>()
            val releaseTargetDelete = CompletableDeferred<Unit>()
            val heldTargetDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    domains.delete(deletedTarget)
                    targetDeleted.complete(backendPid())
                    releaseTargetDelete.await()
                }
            }
            val targetDeleteHolderPid = targetDeleted.await()
            val rejectedMovePid = CompletableDeferred<Int>()
            val rejectedMove = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        rejectedMovePid.complete(backendPid())
                        systems.update(systemId, SystemRequest(deletedTarget, name("moving-system")))
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, rejectedMovePid.await(), targetDeleteHolderPid, releaseTargetDelete)
            heldTargetDelete.await()
            assertIs<BadRequestException>(rejectedMove.await())
            assertEquals(targetDomain, systems.read(systemId)?.domainId)
        }
    }

    @Test
    fun `contract create locks its system and owner team and delete-first rejects without an orphan`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val teams = TeamService(database)
        val contracts = ContractService(database, teams)
        val userId = TestUsers.seed(uniqueEmail("parent-contract"), "pw", role = UserRole.ADMIN)
        val caller = CallerPrincipal(userId, "parent-contract@example.test", setOf(UserRole.ADMIN))

        coroutineScope {
            val systemId = TestContracts.seedSystem("contract-attach")
            val teamId = TestTeams.seed(name("owner-team"))
            val created = CompletableDeferred<Int>()
            val release = CompletableDeferred<Unit>()
            val create = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    contracts.create(ContractCreateRequest(systemId, ContractType.OPENAPI, name("contract"), ownerTeamId = teamId), caller)
                    created.complete(backendPid())
                    release.await()
                }
            }
            val createHolderPid = created.await()
            val systemPid = CompletableDeferred<Int>()
            val systemDelete = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        systemPid.complete(backendPid())
                        TestSystems.service.delete(systemId)
                    }
                }.exceptionOrNull()
            }
            val teamPid = CompletableDeferred<Int>()
            val teamDelete = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        teamPid.complete(backendPid())
                        teams.delete(teamId)
                    }
                }.exceptionOrNull()
            }
            try {
                waitUntilBlocked(database, systemPid.await(), createHolderPid)
                waitUntilBlocked(database, teamPid.await(), createHolderPid)
            } finally {
                release.complete(Unit)
            }
            create.await()
            assertIs<ConflictException>(systemDelete.await())
            assertIs<ConflictException>(teamDelete.await())

            val deletedSystem = TestContracts.seedSystem("contract-system-delete")
            val liveTeam = TestTeams.seed(name("live-team"))
            val systemDeleted = CompletableDeferred<Int>()
            val releaseSystemDelete = CompletableDeferred<Unit>()
            val heldSystemDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    TestSystems.service.delete(deletedSystem)
                    systemDeleted.complete(backendPid())
                    releaseSystemDelete.await()
                }
            }
            val systemDeleteHolderPid = systemDeleted.await()
            val rejectedName = name("rejected-system-contract")
            val rejectedPid = CompletableDeferred<Int>()
            val rejected = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        rejectedPid.complete(backendPid())
                        contracts.create(
                            ContractCreateRequest(deletedSystem, ContractType.OPENAPI, rejectedName, ownerTeamId = liveTeam),
                            caller,
                        )
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, rejectedPid.await(), systemDeleteHolderPid, releaseSystemDelete)
            heldSystemDelete.await()
            assertIs<BadRequestException>(rejected.await())
            assertNull(contracts.findActiveId(deletedSystem, rejectedName))

            val liveSystem = TestContracts.seedSystem("contract-team-delete")
            val deletedTeam = TestTeams.seed(name("deleted-team"))
            val teamDeleted = CompletableDeferred<Int>()
            val releaseTeamDelete = CompletableDeferred<Unit>()
            val heldTeamDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    teams.delete(deletedTeam)
                    teamDeleted.complete(backendPid())
                    releaseTeamDelete.await()
                }
            }
            val teamDeleteHolderPid = teamDeleted.await()
            val teamRejectedName = name("rejected-team-contract")
            val teamRejectedPid = CompletableDeferred<Int>()
            val teamRejected = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        teamRejectedPid.complete(backendPid())
                        contracts.create(
                            ContractCreateRequest(liveSystem, ContractType.OPENAPI, teamRejectedName, ownerTeamId = deletedTeam),
                            caller,
                        )
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, teamRejectedPid.await(), teamDeleteHolderPid, releaseTeamDelete)
            heldTeamDelete.await()
            assertIs<BadRequestException>(teamRejected.await())
            assertNull(contracts.findActiveId(liveSystem, teamRejectedName))
        }
    }

    @Test
    fun `owner transfer serializes with destination team delete in both orders`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val teams = TeamService(database)
        val contracts = ContractService(database, teams)
        val userId = TestUsers.seed(uniqueEmail("owner-transfer"), "pw", role = UserRole.ADMIN)
        val caller = CallerPrincipal(userId, "owner-transfer@example.test", setOf(UserRole.ADMIN))
        val systemId = TestContracts.seedSystem("owner-transfer")
        val oldTeam = TestTeams.seed(name("old-owner"))
        val contractId = contracts.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("owned"), ownerTeamId = oldTeam),
            caller,
        )

        coroutineScope {
            val destination = TestTeams.seed(name("destination"))
            val transferred = CompletableDeferred<Int>()
            val releaseTransfer = CompletableDeferred<Unit>()
            val transfer = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    contracts.transferOwner(contractId, Ownership(destination, null), caller)
                    transferred.complete(backendPid())
                    releaseTransfer.await()
                }
            }
            val transferHolderPid = transferred.await()
            val deletePid = CompletableDeferred<Int>()
            val deletion = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        deletePid.complete(backendPid())
                        teams.delete(destination)
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, deletePid.await(), transferHolderPid, releaseTransfer)
            transfer.await()
            assertIs<ConflictException>(deletion.await())

            val deletedDestination = TestTeams.seed(name("deleted-destination"))
            val deleted = CompletableDeferred<Int>()
            val releaseDelete = CompletableDeferred<Unit>()
            val heldDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    teams.delete(deletedDestination)
                    deleted.complete(backendPid())
                    releaseDelete.await()
                }
            }
            val deleteHolderPid = deleted.await()
            val transferPid = CompletableDeferred<Int>()
            val rejectedTransfer = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        transferPid.complete(backendPid())
                        contracts.transferOwner(contractId, Ownership(deletedDestination, null), caller)
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, transferPid.await(), deleteHolderPid, releaseDelete)
            heldDelete.await()
            assertIs<BadRequestException>(rejectedTransfer.await())
            val owner = suspendTransaction(database) {
                ContractService.Contracts.select(ContractService.Contracts.ownerTeamId)
                    .where { ContractService.Contracts.id eq contractId }
                    .single()[ContractService.Contracts.ownerTeamId]?.value
            }
            assertEquals(destination, owner)
        }
    }

    @Test
    fun `environment create and move serialize with system delete and preserve cascade semantics`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val environments = EnvironmentService(database, FieldCipher(TestEnvironments.TEST_DATA_ENCRYPTION_KEY))

        coroutineScope {
            val attachSystem = TestContracts.seedSystem("env-attach")
            val attached = CompletableDeferred<Pair<UInt, Int>>()
            val releaseAttach = CompletableDeferred<Unit>()
            val create = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    val id = environments.create(
                        EnvironmentRequest(attachSystem, name("environment"), httpBaseUrl = "https://api.example.test"),
                    )
                    attached.complete(id to backendPid())
                    releaseAttach.await()
                }
            }
            val (environmentId, attachHolderPid) = attached.await()
            val deletePid = CompletableDeferred<Int>()
            val deletion = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    deletePid.complete(backendPid())
                    TestSystems.service.delete(attachSystem)
                }
            }
            releaseAfterBlocked(database, deletePid.await(), attachHolderPid, releaseAttach)
            create.await()
            assertEquals(1, deletion.await())
            assertNull(environments.read(environmentId))

            val deletedSystem = TestContracts.seedSystem("env-delete-first")
            val deleted = CompletableDeferred<Int>()
            val releaseDelete = CompletableDeferred<Unit>()
            val heldDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    TestSystems.service.delete(deletedSystem)
                    deleted.complete(backendPid())
                    releaseDelete.await()
                }
            }
            val deleteHolderPid = deleted.await()
            val createPid = CompletableDeferred<Int>()
            val rejectedCreate = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        createPid.complete(backendPid())
                        environments.create(
                            EnvironmentRequest(deletedSystem, name("rejected-env"), httpBaseUrl = "https://api.example.test"),
                        )
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, createPid.await(), deleteHolderPid, releaseDelete)
            heldDelete.await()
            assertIs<BadRequestException>(rejectedCreate.await())

            val oldSystem = TestContracts.seedSystem("env-old")
            val targetSystem = TestContracts.seedSystem("env-target")
            val movingId = environments.create(
                EnvironmentRequest(oldSystem, name("moving-env"), httpBaseUrl = "https://api.example.test"),
            )
            val moved = CompletableDeferred<Int>()
            val releaseMove = CompletableDeferred<Unit>()
            val move = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    environments.update(
                        movingId,
                        EnvironmentRequest(targetSystem, name("moving-env"), httpBaseUrl = "https://api.example.test"),
                    )
                    moved.complete(backendPid())
                    releaseMove.await()
                }
            }
            val moveHolderPid = moved.await()
            val targetDeletePid = CompletableDeferred<Int>()
            val targetDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    targetDeletePid.complete(backendPid())
                    TestSystems.service.delete(targetSystem)
                }
            }
            releaseAfterBlocked(database, targetDeletePid.await(), moveHolderPid, releaseMove)
            move.await()
            assertEquals(1, targetDelete.await())
            assertNull(environments.read(movingId))

            val retainedOld = TestContracts.seedSystem("env-retained-old")
            val deletedTarget = TestContracts.seedSystem("env-deleted-target")
            val retainedId = environments.create(
                EnvironmentRequest(retainedOld, name("retained-env"), httpBaseUrl = "https://api.example.test"),
            )
            val targetDeleted = CompletableDeferred<Int>()
            val releaseTargetDelete = CompletableDeferred<Unit>()
            val heldTargetDelete = async {
                suspendTransaction(database) {
                    maxAttempts = 1
                    TestSystems.service.delete(deletedTarget)
                    targetDeleted.complete(backendPid())
                    releaseTargetDelete.await()
                }
            }
            val targetDeleteHolderPid = targetDeleted.await()
            val movePid = CompletableDeferred<Int>()
            val rejectedMove = async {
                runCatching {
                    suspendTransaction(database) {
                        maxAttempts = 1
                        movePid.complete(backendPid())
                        environments.update(
                            retainedId,
                            EnvironmentRequest(deletedTarget, name("retained-env"), httpBaseUrl = "https://api.example.test"),
                        )
                    }
                }.exceptionOrNull()
            }
            releaseAfterBlocked(database, movePid.await(), targetDeleteHolderPid, releaseTargetDelete)
            heldTargetDelete.await()
            assertIs<BadRequestException>(rejectedMove.await())
            assertEquals(retainedOld, environments.read(retainedId)?.systemId)
        }
    }
}
