package ch.nokillswit.contracts

import ch.nokillswit.infra.db.R2dbcDatabaseKey
import ch.nokillswit.infra.db.active
import ch.nokillswit.notifications.Notification
import ch.nokillswit.notifications.NotificationService
import ch.nokillswit.notifications.NotificationServiceKey
import ch.nokillswit.notifications.NotificationType
import ch.nokillswit.users.UserService
import io.ktor.server.application.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class ReleaseLineReminderService(
    private val database: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase,
    private val notifications: NotificationService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun scan() {
        val cutoff = LocalDate.now(clock).plusDays(30).toString()
        var after = 0u
        while (true) {
            val ids = candidateIds(after, cutoff)
            if (ids.isEmpty()) return
            ids.forEach { id ->
                try {
                    process(id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    log.warn("Could not create lifecycle reminders for release line {}", id, failure)
                }
            }
            after = ids.last()
        }
    }

    private suspend fun candidateIds(after: UInt, cutoff: String): List<UInt> = suspendTransaction(database) {
        val lines = ReleaseLineService.ReleaseLines
        // Canonical ISO dates sort chronologically. Revalidate every fact under the parent lock;
        // this bounded scan is only candidate selection, never the publication guard.
        lines.select(lines.id).where {
            lines.active() and (lines.id greater after) and
                (lines.supportStatus neq SupportStatus.END_OF_LIFE.name) and
                ((lines.deprecatesOn lessEq cutoff) or (lines.supportEndsOn lessEq cutoff))
        }.orderBy(lines.id).limit(100).map { it[lines.id].value }.toList()
    }

    private suspend fun process(lineId: UInt) = suspendTransaction(database) {
        val lines = ReleaseLineService.ReleaseLines
        val initial = lines.selectAll().where { (lines.id eq lineId) and lines.active() }.toList().singleOrNull()
            ?: return@suspendTransaction
        val contractId = initial[lines.contractId].value
        val contracts = ContractService.Contracts
        val contract = contracts.select(contracts.id, contracts.name)
            .where { (contracts.id eq contractId) and contracts.active() }
            .forUpdate()
            .toList().singleOrNull() ?: return@suspendTransaction
        val line = lines.selectAll().where { (lines.id eq lineId) and lines.active() }.toList().singleOrNull()
            ?: return@suspendTransaction
        if (SupportStatus.valueOf(line[lines.supportStatus]) == SupportStatus.END_OF_LIFE) return@suspendTransaction
        val versions = ContractVersionService.ContractVersions
        if (versions.selectAll().where { (versions.contractId eq contractId) and
                (versions.semverMajor eq line[lines.major]) and versions.active() }.count() == 0L
        ) return@suspendTransaction

        val subscriptions = ContractSubscriptionService.ContractSubscriptions
        val users = UserService.Users
        val recipients = subscriptions.innerJoin(users)
            .select(subscriptions.userId)
            .where { (subscriptions.contractId eq contractId) and users.active() }
            .map { it[subscriptions.userId].value }
            .toList()
            .toSet()
        if (recipients.isEmpty()) return@suspendTransaction

        val today = LocalDate.now(clock)
        val facts = listOfNotNull(
            line[lines.deprecatesOn]?.let { Deadline(NotificationType.RELEASE_LINE_DEPRECATION_DUE, LocalDate.parse(it)) },
            line[lines.supportEndsOn]?.let { Deadline(NotificationType.RELEASE_LINE_SUPPORT_END_DUE, LocalDate.parse(it)) },
        )
        val pending = facts.flatMap { deadline ->
            val days = ChronoUnit.DAYS.between(today, deadline.date)
            val bucket = when {
                days > 30 -> return@flatMap emptyList()
                days >= 8 -> 30
                days >= 1 -> 7
                else -> 0
            }
            val stage = when {
                days >= 8 -> "DUE_IN_30_DAYS"
                days >= 1 -> "DUE_IN_7_DAYS"
                days == 0L -> "DUE_TODAY"
                else -> "OVERDUE"
            }
            val params = mapOf(
                "contractName" to contract[contracts.name],
                "major" to line[lines.major].toString(),
                "deadline" to deadline.date.toString(),
                "stage" to stage,
            )
            val key = "$lineId/${deadline.type.name}/${deadline.date}/$bucket"
            recipients.map { recipient ->
                Notification(recipient, deadline.type, params, ContractLinks.contract(contractId), key)
            }
        }
        notifications.createAllInCurrentTransaction(pending, clock.millis())
    }

    private data class Deadline(val type: NotificationType, val date: LocalDate)
    private companion object { val log = LoggerFactory.getLogger(ReleaseLineReminderService::class.java) }
}

fun Application.configureReleaseLineReminders() {
    if (!environment.config.property("lifecycle.reminders.enabled").getString().toBooleanStrict()) return
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val service = ReleaseLineReminderService(attributes[R2dbcDatabaseKey], attributes[NotificationServiceKey])
    scope.launch {
        while (isActive) {
            try {
                service.scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.warn("Could not scan scheduled release-line reminders", failure)
            }
            delay(60 * 60 * 1000L)
        }
    }
    monitor.subscribe(ApplicationStopping) { scope.cancel() }
}

private val log = LoggerFactory.getLogger("ReleaseLineReminders")
