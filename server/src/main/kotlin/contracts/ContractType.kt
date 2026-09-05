package ch.nokillswit.contracts

import kotlinx.serialization.Serializable

/**
 * The contract standards Covenant stores — a CHECK in V9 because the type drives which
 * validator runs. Widening it (a future FILE_FORMAT type, say) is a deliberate migration plus
 * a validator; see `.claude/docs/contract-standards.md` for what each standard is.
 */
@Serializable
enum class ContractType { OPENAPI, ASYNCAPI, ODCS }
