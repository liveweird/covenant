package ch.nokillswit.contracts

import ch.nokillswit.users.UserService
import io.ktor.util.AttributeKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

val ContractSubscriptionServiceKey = AttributeKey<ContractSubscriptionService>("ContractSubscriptionService")

/**
 * Who FOLLOWS a contract (V13) — the recipient set for the contract's notifications. A pure
 * membership join (hard-delete, like team_members): follow is an idempotent insert, unfollow a
 * delete. Anyone who may read a contract may follow it (every authenticated user).
 */
class ContractSubscriptionService(private val database: R2dbcDatabase) {
    object ContractSubscriptions : Table("contract_subscriptions") {
        val contractId = reference("contract_id", ContractService.Contracts)
        val userId = reference("user_id", UserService.Users)
        val createdAt = long("created_at")
        override val primaryKey = PrimaryKey(contractId, userId)
    }

    /** Idempotent; false when the contract does not exist (or is deleted). */
    suspend fun subscribe(contractId: UInt, userId: UInt): Boolean = suspendTransaction(database) {
        val c = ContractService.Contracts
        val exists = c.select(c.id).where { (c.id eq contractId) and (c.markedAsDeleted eq false) }.count() > 0
        if (!exists) return@suspendTransaction false
        ContractSubscriptions.insertIgnore {
            it[ContractSubscriptions.contractId] = contractId
            it[ContractSubscriptions.userId] = userId
            it[createdAt] = System.currentTimeMillis()
        }
        true
    }

    /** The removed row count — 0 when the caller was not following. */
    suspend fun unsubscribe(contractId: UInt, userId: UInt): Int = suspendTransaction(database) {
        ContractSubscriptions.deleteWhere { (ContractSubscriptions.contractId eq contractId) and (ContractSubscriptions.userId eq userId) }
    }

    /** Every follower of the contract — the notification fan-out's recipients (the actor is removed by the caller). */
    suspend fun subscriberIds(contractId: UInt): Set<UInt> = suspendTransaction(database) {
        ContractSubscriptions.select(ContractSubscriptions.userId)
            .where { ContractSubscriptions.contractId eq contractId }
            .map { it[ContractSubscriptions.userId].value }
            .toList()
            .toSet()
    }
}
