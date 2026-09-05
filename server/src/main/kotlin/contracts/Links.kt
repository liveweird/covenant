package ch.nokillswit.contracts

/**
 * The SPA's contract route family, spelled out ONCE server-side for the language-independent
 * links notifications carry (`web/src/utils/contractLinks.ts` is the client twin — keep them in step).
 */
object ContractLinks {
    fun contract(contractId: UInt) = "/contracts/$contractId"
    fun version(contractId: UInt, versionId: UInt) = "/contracts/$contractId/versions/$versionId"
}
