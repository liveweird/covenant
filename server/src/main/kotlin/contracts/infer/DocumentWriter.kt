package ch.nokillswit.contracts.infer

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.dataformat.yaml.util.StringQuotingChecker

/**
 * The ONE place Covenant writes a document — into a response body only, never stored (every
 * other path keeps text byte-exact). Guarantee: `DocumentParser.parse(yaml(tree))` reproduces
 * [ObjectNode] exactly — pinned by a round-trip test over the YAML quoting pitfalls (`"3.1.0"`,
 * `"yes"`, `"null"`, `"007"`, `"1e3"`, `~`, `""`, `: `/`#`/leading `*`/`&`/`@`/`-`, multi-line
 * strings). [InferenceStringQuotingChecker] closes the gap Jackson's default leaves: it already
 * quotes YAML 1.1 boolean/null words, but not leading-indicator characters that make a plain
 * scalar ambiguous with an alias, anchor or block-sequence entry.
 */
object DocumentWriter {
    private val FACTORY = YAMLFactory.builder()
        .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
        .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
        .enable(YAMLGenerator.Feature.ALWAYS_QUOTE_NUMBERS_AS_STRINGS)
        .enable(YAMLGenerator.Feature.INDENT_ARRAYS_WITH_INDICATOR)
        .disable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
        .stringQuotingChecker(InferenceStringQuotingChecker)
        .build()
    private val MAPPER = YAMLMapper(FACTORY)

    /** [root]'s insertion order is preserved (Jackson's `ObjectNode` is a `LinkedHashMap` under the hood). */
    fun yaml(root: ObjectNode): String = MAPPER.writeValueAsString(root)

    private object InferenceStringQuotingChecker : StringQuotingChecker.Default() {
        // A plain YAML scalar starting with one of these is ambiguous (alias/anchor/tag/reserved/
        // block-sequence-entry/flow indicators) — Jackson's default checker does not consider it.
        private val INDICATOR_LEADERS = setOf('*', '&', '!', '@', '`', '"', '\'', '?', '|', '>', '%', '#', ',', '[', ']', '{', '}')

        // Jackson's ALWAYS_QUOTE_NUMBERS_AS_STRINGS only catches `[+-]?[0-9]*(\.[0-9]*)?` — an
        // exponent like "1e3" is a valid YAML 1.1 float too (SnakeYAML reads it back as 1000.0
        // unquoted), so it needs its own check.
        private val NUMBER_LIKE = Regex(
            "^[-+]?(\\.inf|\\.Inf|\\.INF|\\.nan|\\.NaN|\\.NAN|" +
                "0x[0-9a-fA-F_]+|0o[0-7_]+|0b[01_]+|" +
                "(\\d[\\d_]*)(\\.[\\d_]*)?([eE][-+]?\\d+)?|" +
                "\\.[\\d_]+([eE][-+]?\\d+)?)$",
        )

        override fun needToQuoteName(name: String): Boolean = needsExtraQuoting(name) || super.needToQuoteName(name)

        override fun needToQuoteValue(value: String): Boolean = needsExtraQuoting(value) || super.needToQuoteValue(value)

        private fun needsExtraQuoting(value: String): Boolean {
            if (value.isEmpty()) return false
            if (NUMBER_LIKE.matches(value)) return true
            val first = value[0]
            if (first in INDICATOR_LEADERS) return true
            // "-" (or "- ") at the start reads as a block-sequence entry indicator unless quoted.
            return first == '-' && (value.length == 1 || value[1] == ' ')
        }
    }
}
