package ch.nokillswit.contracts.infer

private val INTEGER_SEGMENT = Regex("^\\d+$")
private val UUID_SEGMENT = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
private val HEX_SEGMENT = Regex("^[0-9a-fA-F]+$")
private val OPAQUE_SEGMENT = Regex("^[A-Za-z0-9_-]+$")
private const val MIN_OPAQUE_LENGTH = 16

/**
 * Path templating — `OpenApiInference`'s one caller. A segment becomes a `{param}` when it looks
 * like an id (an integer, a UUID, a long hex string, or an opaque token — at least 16 chars of
 * letters/digits/`-`/`_` with at least one digit); never merged across samples (`/users/alice`
 * and `/users/bob` stay two literal paths — a documented limit, see `contract-standards.md`).
 */
object PathTemplates {
    enum class ParamKind { INTEGER, UUID, STRING }

    data class TemplateParam(val name: String, val kind: ParamKind)
    data class Templated(val template: String, val params: List<TemplateParam>)

    fun classify(segment: String): ParamKind? = when {
        INTEGER_SEGMENT.matches(segment) -> ParamKind.INTEGER
        UUID_SEGMENT.matches(segment) -> ParamKind.UUID
        HEX_SEGMENT.matches(segment) && segment.length >= MIN_OPAQUE_LENGTH -> ParamKind.STRING
        OPAQUE_SEGMENT.matches(segment) && segment.length >= MIN_OPAQUE_LENGTH && segment.any { it.isDigit() } -> ParamKind.STRING
        else -> null
    }

    /** Templates one path; parameter names come from the preceding STATIC segment, singularised + `Id`. */
    fun template(path: String): Templated {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        val used = mutableSetOf<String>()
        val params = mutableListOf<TemplateParam>()
        val out = segments.mapIndexed { i, segment ->
            val kind = classify(segment) ?: return@mapIndexed segment
            val name = paramName(segments.getOrNull(i - 1), used)
            params += TemplateParam(name, kind)
            "{$name}"
        }
        return Templated("/" + out.joinToString("/"), params)
    }

    private fun paramName(preceding: String?, used: MutableSet<String>): String {
        val base = preceding?.let { singularize(it) }?.takeIf { it.isNotEmpty() } ?: "id"
        var candidate = if (base == "id") "id" else "${base}Id"
        var suffix = 2
        while (!used.add(candidate)) {
            candidate = if (base == "id") "id$suffix" else "${base}Id$suffix"
            suffix++
        }
        return candidate
    }

    /** `ies`→`y`, a trailing `s` drops unless the word ends `ss` — naive on purpose, good enough for a param name. */
    internal fun singularize(word: String): String = when {
        word.endsWith("ies") && word.length > "ies".length -> word.dropLast(3) + "y"
        word.endsWith("ss") -> word
        word.endsWith("s") && word.length > 1 -> word.dropLast(1)
        else -> word
    }

    /** `method` + PascalCased static segments + `By<Param>` (`getOrdersByOrderId`) — the caller dedupes. */
    fun operationId(method: String, template: String, params: List<TemplateParam>): String {
        val segments = template.trim('/').split('/').filter { it.isNotEmpty() }
        val staticPart = segments.filterNot { it.startsWith("{") }.joinToString("") { pascalCase(it) }
        val byPart = if (params.isNotEmpty()) "By" + params.joinToString("And") { pascalCase(it.name) } else ""
        return method.lowercase() + staticPart + byPart
    }

    internal fun pascalCase(raw: String): String =
        raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
}
