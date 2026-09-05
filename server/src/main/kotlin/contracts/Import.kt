package ch.nokillswit.contracts

import ch.nokillswit.authz.CallerPrincipal
import ch.nokillswit.authz.ForbiddenException
import ch.nokillswit.plugins.isUniqueViolation
import io.ktor.server.plugins.BadRequestException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * Import (`POST /contracts/import`, dry-run `POST /contracts/import/check`): each item is one
 * document landing as a new version of a contract that may or may not exist yet — report & skip,
 * Toadie's pipeline shape. Import ALWAYS waives soft findings (the point is getting the batch
 * in), so a flawed document stores as `*_WITH_FINDINGS`; only HARD findings (`INVALID`), the
 * SemVer rules (`INVALID`/`CONFLICT`), a type mismatch with the existing contract (`INVALID`)
 * and the writer rule (`FORBIDDEN`) skip an item.
 */
const val MAX_IMPORT_ITEMS = 20

@Serializable
data class ImportItem(
    val systemId: UInt,
    val type: ContractType,
    val name: String,
    val description: String? = null,
    /** Required only when the contract does not exist yet (an existing one keeps its owner). */
    val ownerTeamId: UInt? = null,
    val ownerUserId: UInt? = null,
    val version: String,
    val content: String,
)

@Serializable
data class ImportRequest(val items: List<ImportItem>)

@Serializable
enum class ImportStatus {
    /** A new contract AND its first version were stored. */
    CREATED,
    CREATED_WITH_FINDINGS,
    /** The contract existed; a new version was added. */
    VERSION_ADDED,
    VERSION_ADDED_WITH_FINDINGS,
    /** A HARD finding, a bad/too-low SemVer, an unknown system/owner, or a type mismatch with the existing contract. */
    INVALID,
    /** The version already exists on that contract — nothing overwritten. */
    CONFLICT,
    /** The caller may not write the existing contract (or assign that owner). */
    FORBIDDEN,
    /** An unexpected storage failure for this item; the rest of the batch proceeded. */
    ERROR,
}

@Serializable
data class ImportItemResult(
    val index: Int,
    val name: String,
    val version: String,
    val status: ImportStatus,
    val contractId: UInt? = null,
    val versionId: UInt? = null,
    val message: String? = null,
    val errors: Int = 0,
    val warnings: Int = 0,
)

@Serializable
data class ImportResponse(val results: List<ImportItemResult>)

/**
 * The shared per-item classification: the real run stores, the dry run predicts. Both walk the
 * same steps in the same order, so `import/check` cannot drift from `import`.
 */
class ContractImporter(
    private val contracts: ContractService,
    private val versions: ContractVersionService,
    private val checks: ch.nokillswit.contracts.checks.ChecksService,
) {
    suspend fun run(request: ImportRequest, caller: CallerPrincipal, store: Boolean): List<ImportItemResult> {
        // The dry run must predict intra-batch duplicates the way the real run would hit them.
        val seen = mutableSetOf<Pair<UInt, String>>()
        return request.items.mapIndexed { index, raw -> one(index, raw, caller, store, seen) }
    }

    private suspend fun one(
        index: Int,
        raw: ImportItem,
        caller: CallerPrincipal,
        store: Boolean,
        seen: MutableSet<Pair<UInt, String>>,
    ): ImportItemResult {
        val base = ImportItemResult(index = index, name = raw.name.trim(), version = raw.version.trim(), status = ImportStatus.ERROR)
        return try {
            val item = raw.copy(name = ch.nokillswit.infra.validation.sanitizeSingleLine(raw.name, "Name"), version = raw.version.trim())
            validateContractNameAndDescription(item.name, item.description)
            val semver = SemVer.parse(item.version)
            val existingId = contracts.findActiveId(item.systemId, item.name)
            val key = item.systemId to item.name.lowercase()
            val seenBefore = key in seen
            val rejection =
                if (existingId != null) rejectExisting(base, item, semver, existingId, caller) else rejectNew(item, caller, seenBefore)
            if (rejection != null) return rejection
            // The check runs in both modes: the dry run reports the findings it WOULD store.
            val report = checks.check(item.type, item.content, declaredVersion = item.version, lifecycle = Lifecycle.DRAFT)
            if (report.hardFindings.isNotEmpty()) {
                return base.copy(status = ImportStatus.INVALID, message = report.hardFindings.joinToString("; ") { it.message })
            }
            val withFindings = report.softErrors.isNotEmpty()
            val predicted = base.copy(
                status = statusOf(added = existingId != null || seenBefore, withFindings = withFindings),
                contractId = existingId,
                errors = report.errors,
                warnings = report.warnings,
                message = report.softErrors.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.code}: ${it.message}" },
            )
            seen.add(key)
            if (store) store(predicted, item, existingId, caller) else predicted
        } catch (e: CancellationException) {
            throw e
        } catch (e: ForbiddenException) {
            base.copy(status = ImportStatus.FORBIDDEN, message = e.message)
        } catch (e: BadRequestException) {
            base.copy(status = ImportStatus.INVALID, message = e.message)
        } catch (e: ch.nokillswit.authz.ConflictException) {
            base.copy(status = ImportStatus.CONFLICT, message = e.message)
        } catch (e: ch.nokillswit.authz.NotFoundException) {
            base.copy(status = ImportStatus.INVALID, message = e.message)
        } catch (e: Exception) {
            if (e.isUniqueViolation()) base.copy(status = ImportStatus.CONFLICT, message = ALREADY_EXISTS)
            else base.copy(status = ImportStatus.ERROR, message = e.message ?: "Storage failed")
        }
    }

    /** The reasons an item cannot land on an EXISTING contract, in the order the real run hits them. */
    private suspend fun rejectExisting(
        base: ImportItemResult,
        item: ImportItem,
        semver: SemVer,
        existingId: UInt,
        caller: CallerPrincipal,
    ): ImportItemResult? {
        val type = contracts.typeOf(existingId)
        if (type != item.type) {
            return base.copy(status = ImportStatus.INVALID, message = "The existing contract is $type, not ${item.type}")
        }
        contracts.authorizeWrite(caller, existingId)
        if (versions.exists(existingId, item.version)) return base.copy(status = ImportStatus.CONFLICT, message = ALREADY_EXISTS)
        val top = versions.highestOf(existingId)
        if (top != null && semver <= top) {
            return base.copy(
                status = ImportStatus.INVALID,
                message = "Version ${item.version} must be greater than the highest existing version $top",
            )
        }
        return null
    }

    /** A NEW contract needs an assignable owner (the create rule) — checked up front so the dry run predicts the 403 too. */
    private suspend fun rejectNew(item: ImportItem, caller: CallerPrincipal, seenBefore: Boolean): ImportItemResult? {
        if (!seenBefore) {
            val ownership = ownershipOf(item.ownerTeamId, item.ownerUserId)
            requireOwnerAssignable(caller, ownership, contracts.viewer(caller).teamIds)
        }
        return null
    }

    private suspend fun store(predicted: ImportItemResult, item: ImportItem, existingId: UInt?, caller: CallerPrincipal): ImportItemResult {
        val contractId = existingId ?: contracts.create(
            ContractCreateRequest(item.systemId, item.type, item.name, item.description, item.ownerTeamId, item.ownerUserId),
            caller,
        )
        val saved = versions.create(
            contractId,
            item.type,
            VersionCreateRequest(item.version, item.content),
            caller.userId,
            allowInvalid = true,
        )
        return predicted.copy(contractId = contractId, versionId = saved.response.id)
    }

    private fun statusOf(added: Boolean, withFindings: Boolean) = when {
        added && withFindings -> ImportStatus.VERSION_ADDED_WITH_FINDINGS
        added -> ImportStatus.VERSION_ADDED
        withFindings -> ImportStatus.CREATED_WITH_FINDINGS
        else -> ImportStatus.CREATED
    }

    private companion object {
        const val ALREADY_EXISTS = "This version already exists on the contract — nothing overwritten"
    }
}
