package ch.nokillswit.contracts.infer

import ch.nokillswit.contracts.render.RenderBudget
import ch.nokillswit.contracts.tryit.TryCatalog
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import io.ktor.http.HttpStatusCode
import java.net.URI

/**
 * HTTP exchanges → OpenAPI 3.1. Paths are templated (`PathTemplates`) and operations grouped by
 * (method, template); query/request-body/response/header/security rules are documented in
 * `.claude/docs/contract-standards.md` "Inference". 3.1 documents are compared through the 3.0
 * model by the breaking-change differ, so this builder's type ARRAYS stay invisible to it —
 * documented there, not a bug here.
 */
object OpenApiInference {
    private val NODES = JsonNodeFactory.instance

    private val HEADER_DENY_EXACT = setOf(
        "date", "server", "connection", "transfer-encoding", "keep-alive", "vary", "cache-control", "pragma", "expires", "age", "via",
        "alt-svc", "strict-transport-security", "x-content-type-options", "x-frame-options", "x-xss-protection", "referrer-policy",
        "x-powered-by", "x-served-by", "x-timer",
    )
    private val HEADER_DENY_PREFIXES = listOf("content-", "access-control-", "set-cookie", "cf-", "x-cache", "x-amz-")

    private val API_KEY_HEADER_NAMES = listOf(
        "x-api-key", "api-key", "apikey", "x-auth-token", "x-access-token", "x-token", "x-api-token", "x-client-secret", "x-session-token",
    )

    private enum class SchemeKind { HTTP_BEARER, HTTP_BASIC, HTTP_DIGEST, API_KEY }
    private data class SecurityDetection(val key: String, val kind: SchemeKind, val headerName: String)

    fun build(request: InferRequest, budget: RenderBudget, notes: Notes): ObjectNode {
        val root = NODES.objectNode()
        root.put("openapi", "3.1.0")
        buildInfo(root, request)
        buildServers(root, request.http)
        val opIds = mutableSetOf<String>()
        val security = LinkedHashMap<String, SecurityDetection>()
        buildPaths(root, request.http, budget, notes, opIds, security)
        finalizeSecuritySchemes(root, security, notes)
        notes.info("INFER_SAMPLES_MERGED", "${request.http.size} HTTP exchange sample(s) were merged into this document", "/paths")
        return root
    }

    private fun buildInfo(root: ObjectNode, request: InferRequest) {
        val info = root.putObject("info")
        info.put("title", request.name?.trim()?.takeIf { it.isNotEmpty() } ?: firstHost(request.http) ?: "Inferred API")
        info.put("version", request.version?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0.0")
        info.put("description", "")
    }

    private fun buildServers(root: ObjectNode, http: List<HttpExchangeSample>) {
        val origins = http.mapNotNull { origin(it.url) }.distinct()
        if (origins.isEmpty()) return
        val servers = root.putArray("servers")
        origins.forEach { servers.addObject().put("url", it) }
    }

    private fun buildPaths(
        root: ObjectNode,
        http: List<HttpExchangeSample>,
        budget: RenderBudget,
        notes: Notes,
        opIds: MutableSet<String>,
        security: LinkedHashMap<String, SecurityDetection>,
    ) {
        val paths = root.putObject("paths")
        val templated = http.map { it to PathTemplates.template(pathOf(it.url)) }
        templated.groupBy { it.second.template }.forEach { (template, entries) ->
            val params = mergeParams(entries.map { it.second })
            if (params.isNotEmpty()) {
                notes.info(
                    "INFER_PATH_TEMPLATED",
                    "Path '$template' was inferred from a literal example — verify the parameter boundaries",
                    "/paths/${TryCatalog.esc(template)}",
                )
            }
            val pathItem = paths.putObject(template)
            entries.map { it.first }.groupBy { it.method.uppercase() }.forEach { (method, exchanges) ->
                val op = buildOperation(method, template, params, exchanges, budget, notes, opIds, security)
                pathItem.set<ObjectNode>(method.lowercase(), op)
            }
        }
    }

    private fun mergeParams(instances: List<PathTemplates.Templated>): List<PathTemplates.TemplateParam> {
        if (instances.isEmpty()) return emptyList()
        return instances.first().params.mapIndexed { i, param ->
            val kinds = instances.map { it.params[i].kind }.toSet()
            if (kinds.size > 1) param.copy(kind = PathTemplates.ParamKind.STRING) else param
        }
    }

    private fun buildOperation(
        method: String,
        template: String,
        params: List<PathTemplates.TemplateParam>,
        exchanges: List<HttpExchangeSample>,
        budget: RenderBudget,
        notes: Notes,
        opIds: MutableSet<String>,
        security: LinkedHashMap<String, SecurityDetection>,
    ): ObjectNode {
        val op = NODES.objectNode()
        op.put("operationId", uniqueOperationId(method, template, params, opIds))
        val opPointer = "/paths/${TryCatalog.esc(template)}/${method.lowercase()}"
        val parameters = NODES.arrayNode()
        addPathParameters(parameters, params)
        addQueryParameters(parameters, exchanges)
        if (!parameters.isEmpty) op.set<ArrayNode>("parameters", parameters) // an empty list is noise, not information
        buildRequestBody(op, exchanges, budget, notes, opPointer)
        buildResponses(op, exchanges, budget, notes, opPointer)
        buildSecurity(op, exchanges, security)
        return op
    }

    private fun uniqueOperationId(
        method: String,
        template: String,
        params: List<PathTemplates.TemplateParam>,
        used: MutableSet<String>,
    ): String {
        val base = PathTemplates.operationId(method, template, params)
        var candidate = base
        var n = 2
        while (!used.add(candidate)) {
            candidate = "$base$n"
            n++
        }
        return candidate
    }

    private fun addPathParameters(parameters: ArrayNode, params: List<PathTemplates.TemplateParam>) {
        params.forEach { p ->
            val node = parameters.addObject()
            node.put("name", p.name)
            node.put("in", "path")
            node.put("required", true)
            node.putObject("schema").put("type", paramSchemaType(p.kind))
        }
    }

    private fun paramSchemaType(kind: PathTemplates.ParamKind): String = when (kind) {
        PathTemplates.ParamKind.INTEGER -> "integer"
        PathTemplates.ParamKind.UUID, PathTemplates.ParamKind.STRING -> "string"
    }

    private fun addQueryParameters(parameters: ArrayNode, exchanges: List<HttpExchangeSample>) {
        val keys = LinkedHashSet<String>()
        exchanges.forEach { keys += it.query.keys }
        keys.forEach { key ->
            val values = exchanges.mapNotNull { it.query[key] }
            val required = exchanges.all { key in it.query }
            val node = parameters.addObject()
            node.put("name", key)
            node.put("in", "query")
            node.put("required", required)
            node.putObject("schema").put("type", scalarType(values))
        }
    }

    private fun scalarType(values: List<String>): String = when {
        values.isEmpty() -> "string"
        values.all { it == "true" || it == "false" } -> "boolean"
        values.all { it.toLongOrNull() != null } -> "integer"
        values.all { it.toDoubleOrNull() != null } -> "number"
        else -> "string"
    }

    private fun buildRequestBody(op: ObjectNode, exchanges: List<HttpExchangeSample>, budget: RenderBudget, notes: Notes, pointer: String) {
        val withBody = exchanges.filter { !it.requestBody.isNullOrEmpty() }
        if (withBody.isEmpty()) return
        val rb = op.putObject("requestBody")
        rb.put("required", withBody.size == exchanges.size)
        val content = rb.putObject("content")
        withBody.groupBy { mediaType(it.requestContentType) }.forEach { (mt, group) ->
            addMediaTypeSchema(content, mt, group.map { it.requestBody!! }, budget, notes, "$pointer/requestBody")
        }
    }

    private fun buildResponses(op: ObjectNode, exchanges: List<HttpExchangeSample>, budget: RenderBudget, notes: Notes, pointer: String) {
        val responses = op.putObject("responses")
        exchanges.groupBy { it.status }.forEach { (status, group) ->
            val statusNode = responses.putObject(status.toString())
            statusNode.put("description", responseDescription(status))
            addResponseHeaders(statusNode, group)
            val withBody = group.filter { !it.responseBody.isNullOrEmpty() }
            if (withBody.isNotEmpty()) {
                val content = statusNode.putObject("content")
                withBody.groupBy { mediaType(it.responseContentType) }.forEach { (mt, g) ->
                    addMediaTypeSchema(content, mt, g.map { it.responseBody!! }, budget, notes, "$pointer/responses/$status")
                }
            }
        }
    }

    private fun addMediaTypeSchema(
        content: ObjectNode,
        mediaType: String,
        bodies: List<String>,
        budget: RenderBudget,
        notes: Notes,
        pointer: String,
    ) {
        val mtNode = content.putObject(mediaType)
        if (isJsonMediaType(mediaType)) {
            val samples = bodies.mapNotNull { parseJsonOrNull(it) }
            mtNode.set<ObjectNode>(
                "schema",
                SchemaInference.infer(samples, budget, notes, "$pointer/content/${TryCatalog.esc(mediaType)}/schema"),
            )
        } else {
            notes.info("INFER_BODY_NOT_JSON", "The '$mediaType' body is not JSON — only the media type is recorded", pointer)
        }
    }

    private fun responseDescription(status: Int): String {
        val known = HttpStatusCode.allStatusCodes.any { it.value == status }
        return if (known) HttpStatusCode.fromValue(status).description.ifBlank { "Response" } else "Response"
    }

    private fun addResponseHeaders(statusNode: ObjectNode, group: List<HttpExchangeSample>) {
        val names = group.flatMap { it.responseHeaders }.map { it.lowercase() }.toSortedSet().filterNot { isDeniedResponseHeader(it) }
        if (names.isEmpty()) return
        val headers = statusNode.putObject("headers")
        names.forEach { name -> headers.putObject(name).putObject("schema").put("type", "string") }
    }

    private fun isDeniedResponseHeader(name: String): Boolean =
        name in HEADER_DENY_EXACT || HEADER_DENY_PREFIXES.any { name.startsWith(it) }

    private fun buildSecurity(op: ObjectNode, exchanges: List<HttpExchangeSample>, used: LinkedHashMap<String, SecurityDetection>) {
        val perExchange = exchanges.map { securityDetections(it).associateBy { d -> d.key } }
        val common = perExchange.map { it.keys }.reduceOrNull { a, b -> a intersect b } ?: emptySet()
        if (common.isEmpty()) return
        val securityArr = op.putArray("security")
        common.sorted().forEach { key ->
            used.putIfAbsent(key, perExchange.first().getValue(key))
            securityArr.addObject().putArray(key)
        }
    }

    private fun securityDetections(exchange: HttpExchangeSample): List<SecurityDetection> {
        val out = mutableListOf<SecurityDetection>()
        if (exchange.requestHeaders.any { it.equals("authorization", ignoreCase = true) }) {
            out += when (exchange.authorizationScheme?.lowercase()) {
                "bearer" -> SecurityDetection("bearerAuth", SchemeKind.HTTP_BEARER, "Authorization")
                "basic" -> SecurityDetection("basicAuth", SchemeKind.HTTP_BASIC, "Authorization")
                "digest" -> SecurityDetection("digestAuth", SchemeKind.HTTP_DIGEST, "Authorization")
                else -> SecurityDetection("authorizationApiKey", SchemeKind.API_KEY, "Authorization")
            }
        }
        API_KEY_HEADER_NAMES.forEach { candidate ->
            if (exchange.requestHeaders.any { it.equals(candidate, ignoreCase = true) }) {
                out += SecurityDetection(camelCase(candidate), SchemeKind.API_KEY, headerDisplayName(candidate))
            }
        }
        return out
    }

    private fun finalizeSecuritySchemes(root: ObjectNode, used: Map<String, SecurityDetection>, notes: Notes) {
        if (used.isEmpty()) return
        val schemes = root.putObject("components").putObject("securitySchemes")
        used.forEach { (key, detection) ->
            val node = schemes.putObject(key)
            addSecurityScheme(node, detection)
            notes.info(
                "INFER_SECURITY_DETECTED",
                "A '${detection.headerName}' header suggests the '$key' security scheme",
                "/components/securitySchemes/${TryCatalog.esc(key)}",
            )
        }
    }

    private fun addSecurityScheme(node: ObjectNode, detection: SecurityDetection) {
        when (detection.kind) {
            SchemeKind.HTTP_BEARER -> {
                node.put("type", "http")
                node.put("scheme", "bearer")
            }
            SchemeKind.HTTP_BASIC -> {
                node.put("type", "http")
                node.put("scheme", "basic")
            }
            SchemeKind.HTTP_DIGEST -> {
                node.put("type", "http")
                node.put("scheme", "digest")
            }
            SchemeKind.API_KEY -> {
                node.put("type", "apiKey")
                node.put("in", "header")
                node.put("name", detection.headerName)
            }
        }
    }

    private fun camelCase(hyphenated: String): String {
        val parts = hyphenated.split("-")
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun headerDisplayName(lower: String): String =
        lower.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun mediaType(contentType: String?): String = contentType?.trim()?.takeIf { it.isNotEmpty() } ?: "application/json"

    private fun origin(url: String): String? = runCatching {
        val uri = URI(url)
        if (uri.scheme == null || uri.host == null) {
            null
        } else {
            val portSuffix = if (uri.port !in setOf(-1, 80, 443)) ":${uri.port}" else ""
            "${uri.scheme}://${uri.host}$portSuffix"
        }
    }.getOrNull()

    private fun firstHost(http: List<HttpExchangeSample>): String? =
        http.firstNotNullOfOrNull { runCatching { URI(it.url).host }.getOrNull() }

    private fun pathOf(url: String): String {
        if (url.startsWith("/")) return url.substringBefore('?').ifEmpty { "/" }
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return uri.path.ifEmpty { "/" }
    }
}
