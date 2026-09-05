package ch.nokillswit.contracts.render

/**
 * The renderer's bounds against hostile documents (deep nesting, ref bombs, a 25k-property
 * object): every schema node consumed counts, past [maxNodes] or [maxDepth] the walk emits a
 * `TRUNCATED` marker instead of descending, and the response says so once.
 */
class RenderBudget(val maxDepth: Int = DEFAULT_MAX_DEPTH, val maxNodes: Int = DEFAULT_MAX_NODES) {
    private var used = 0
    var truncated: Boolean = false
        private set

    /** Consumes one node; false (and marks the render truncated) once the budget is spent. */
    fun take(depth: Int): Boolean {
        if (depth > maxDepth || used >= maxNodes) {
            truncated = true
            return false
        }
        used++
        return true
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 32
        const val DEFAULT_MAX_NODES = 20_000
    }
}
