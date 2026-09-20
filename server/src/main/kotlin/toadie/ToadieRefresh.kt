package ch.nokillswit.toadie

import io.ktor.server.application.*
import io.ktor.util.AttributeKey
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory

val ToadieRefreshCoordinatorKey = AttributeKey<ToadieRefreshCoordinator>("ToadieRefreshCoordinator")

class ToadieRefreshCoordinator(
    private val service: ToadieService,
    private val client: ToadieGraphqlClient,
    private val scope: CoroutineScope,
) {
    private val slots = Semaphore(2)

    suspend fun request(connectionId: UInt, force: Boolean): RefreshClaimResult {
        val (result, claim) = service.claimRefresh(connectionId, force)
        if (claim != null) scope.launch { run(claim) }
        return result
    }

    suspend fun scan() {
        service.dueConnectionIds().forEach { id ->
            try {
                request(id, force = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                log.warn("Could not queue scheduled Toadie refresh for connection {}", id)
            }
        }
    }

    private suspend fun run(claim: RefreshClaim) {
        try {
            slots.withPermit {
                try {
                    service.publish(claim, client.fetch(claim.config))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: ToadieFetchException) {
                    service.fail(claim, failure.code)
                } catch (failure: Exception) {
                    log.warn("Toadie refresh failed for connection {}", claim.connectionId, failure)
                    service.fail(claim, "REFRESH_FAILED")
                }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { service.release(claim) }
            throw cancelled
        }
    }

    private companion object { val log = LoggerFactory.getLogger(ToadieRefreshCoordinator::class.java) }
}

fun Application.configureToadieRefresh() {
    val applicationLog = log
    val client = if (attributes.contains(ToadieGraphqlClientKey)) attributes[ToadieGraphqlClientKey] else
        HttpToadieGraphqlClient().also { attributes.put(ToadieGraphqlClientKey, it) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val coordinator = ToadieRefreshCoordinator(attributes[ToadieServiceKey], client, scope)
    attributes.put(ToadieRefreshCoordinatorKey, coordinator)
    scope.launch {
        while (isActive) {
            try {
                coordinator.scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                applicationLog.warn("Could not scan scheduled Toadie refreshes")
            }
            delay(30_000)
        }
    }
    monitor.subscribe(ApplicationStopping) {
        scope.cancel()
    }
    monitor.subscribe(ApplicationStopped) {
        (client as? AutoCloseable)?.close()
    }
}
