package ch.nokillswit

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.contracts.ContractCreateRequest
import ch.nokillswit.contracts.ContractService
import ch.nokillswit.contracts.ContractType
import ch.nokillswit.teams.TeamService
import ch.nokillswit.users.UserRole
import io.r2dbc.spi.R2dbcTransientException
import io.ktor.server.testing.testApplication
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** The row-lock scope used by database mutations and Kafka dispatch authorization. */
class ContractLockingTest {
    private fun name(prefix: String) = "$prefix-${UUID.randomUUID().toString().substring(0, 8)}"

    @Test
    fun `contract creation locks only the assigned team membership`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val teams = TeamService(database)
        val contracts = ContractService(database, teams)
        val email = uniqueEmail("create-lock-scope")
        val userId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val caller = CallerPrincipal(userId, email, emptySet())
        val assignedTeam = TestTeams.seed(name("assigned"), listOf(userId))
        val unrelatedTeam = TestTeams.seed(name("create-unrelated"), listOf(userId))
        val systemId = TestContracts.seedSystem("create-lock-scope")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val creation = async {
                suspendTransaction(database) {
                    contracts.create(
                        ContractCreateRequest(
                            systemId, ContractType.OPENAPI, name("created"), ownerTeamId = assignedTeam,
                        ),
                        caller,
                    )
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val assignedRemoval = async { teams.removeMember(assignedTeam, userId) }
            val unrelatedRemoval = async { teams.removeMember(unrelatedTeam, userId) }
            withTimeout(2_000) { unrelatedRemoval.await() }
            assertFalse(assignedRemoval.isCompleted)
            release.complete(Unit)
            creation.await()
            assignedRemoval.await()
        }
    }

    @Test
    fun `writer lock holds only the owning membership and rechecks revocation before dispatch`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val teams = TeamService(database)
        val contracts = ContractService(database, teams)
        val email = uniqueEmail("lock-scope")
        val userId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val caller = CallerPrincipal(userId, email, emptySet())
        val owningTeam = TestTeams.seed(name("owning"), listOf(userId))
        val unrelatedTeam = TestTeams.seed(name("unrelated"), listOf(userId))
        val systemId = TestContracts.seedSystem("lock-scope")
        val contractId = contracts.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("contract"), ownerTeamId = owningTeam),
            caller,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val dispatch = async {
                contracts.withWriterLock(caller, contractId) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            val relevantRemoval = async { teams.removeMember(owningTeam, userId) }
            val unrelatedRemoval = async { teams.removeMember(unrelatedTeam, userId) }
            withTimeout(2_000) { unrelatedRemoval.await() }
            assertFalse(relevantRemoval.isCompleted, "the owning membership must remain locked through dispatch")
            release.complete(Unit)
            dispatch.await()
            relevantRemoval.await()
        }

        val sideEffects = AtomicInteger()
        val denied = runCatching {
            contracts.withWriterLock(caller, contractId) { sideEffects.incrementAndGet() }
        }.exceptionOrNull()
        assertIs<ForbiddenException>(denied)
        assertEquals(0, sideEffects.get(), "revocation must be observed before the external action begins")
    }

    @Test
    fun `personal ownership takes no roster lock and external action is never retried`() = testApplication {
        usePostgresTestcontainer()
        val database = sharedDatabaseForTests()
        val teams = TeamService(database)
        val contracts = ContractService(database, teams)
        val email = uniqueEmail("lock-personal")
        val userId = TestUsers.seed(email, "pw", role = UserRole.USER)
        val caller = CallerPrincipal(userId, email, emptySet())
        val unrelatedTeam = TestTeams.seed(name("personal-unrelated"), listOf(userId))
        val systemId = TestContracts.seedSystem("lock-personal")
        val contractId = contracts.create(
            ContractCreateRequest(systemId, ContractType.OPENAPI, name("personal"), ownerUserId = userId),
            caller,
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        coroutineScope {
            val dispatch = async {
                contracts.withWriterLock(caller, contractId) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            entered.await()
            withTimeout(2_000) { teams.removeMember(unrelatedTeam, userId) }
            release.complete(Unit)
            dispatch.await()
        }

        val executions = AtomicInteger()
        val failure = runCatching {
            contracts.withWriterLock(caller, contractId) {
                executions.incrementAndGet()
                throw object : R2dbcTransientException("simulated serialization failure", "40001") {}
            }
        }.exceptionOrNull()
        assertIs<R2dbcTransientException>(failure)
        assertEquals(1, executions.get(), "an external publish must not be retried after an ambiguous failure")
    }
}
