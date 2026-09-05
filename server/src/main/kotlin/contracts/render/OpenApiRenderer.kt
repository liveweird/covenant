package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * OpenAPI 3.0 / 3.1 → [OpenApiModel]: operations in path order with path-level parameters merged
 * (the operation wins on name + location), `$ref`'d parameters / request bodies / responses /
 * headers / examples followed one hop, 3.1 `pathItems` refs dereferenced, tags in declared order
 * with undeclared ones appended, `components.schemas` and the security schemes.
 */
class OpenApiRenderer(private val root: JsonNode, budget: RenderBudget) {
    private val dialect = if ((Nodes.text(root, "openapi") ?: "").startsWith("3.0")) SchemaDialect.OPENAPI_30 else SchemaDialect.JSON_SCHEMA
    private val walker = SchemaWalker(root, budget, dialect)

    fun render(): OpenApiModel {
        val operations = Nodes.fields(root.path("paths")).flatMap { (path, item) ->
            pathItem(path, "/paths/${JsonPointers.escape(path)}", item)
        }
        val webhooks = Nodes.fields(root.path("webhooks")).flatMap { (name, item) ->
            pathItem(name, "/webhooks/${JsonPointers.escape(name)}", item)
        }
        val declared = Shared.tags(root.path("tags"))
        val used = (operations + webhooks).flatMap { it.tags }.distinct()
        val tags = declared + used.filter { u -> declared.none { it.name == u } }.map { TagView(it) }
        return OpenApiModel(
            info = Shared.info(root.path("info")),
            servers = servers(root.path("servers")),
            tags = tags,
            operations = operations,
            webhooks = webhooks,
            security = Shared.securityRequirements(root.path("security")).orEmpty(),
            securitySchemes = Shared.securitySchemes(root, root.path("components").path("securitySchemes"), "/components/securitySchemes"),
            schemas = Nodes.fields(root.path("components").path("schemas")).map { (name, s) ->
                val pointer = "/components/schemas/${JsonPointers.escape(name)}"
                NamedSchemaView(name, pointer, walker.node(s, pointer))
            },
            externalDocs = Shared.externalDocs(root.path("externalDocs")),
        )
    }

    private fun pathItem(path: String, pointer: String, raw: JsonNode): List<OperationView> {
        val item = Shared.deref(root, raw)
        val itemPointer = Shared.pointerOf(raw, pointer)
        val shared = parameters(item.path("parameters"), "$itemPointer/parameters")
        return METHODS.mapNotNull { method ->
            val op = item.path(method).takeIf { it.isObject } ?: return@mapNotNull null
            val opPointer = "$itemPointer/$method"
            val own = parameters(op.path("parameters"), "$opPointer/parameters")
            OperationView(
                pointer = opPointer,
                method = method.uppercase(),
                path = path,
                operationId = Nodes.text(op, "operationId"),
                summary = Nodes.text(op, "summary") ?: Nodes.text(item, "summary"),
                description = Nodes.text(op, "description") ?: Nodes.text(item, "description"),
                tags = Nodes.strings(op, "tags"),
                deprecated = Nodes.bool(op, "deprecated"),
                parameters = own + shared.filter { s -> own.none { it.name == s.name && it.location == s.location } },
                requestBody = op.path("requestBody").takeIf { it.isObject }?.let { requestBody(it, "$opPointer/requestBody") },
                responses = Nodes.fields(op.path("responses")).map { (status, r) ->
                    response(status, r, "$opPointer/responses/${JsonPointers.escape(status)}")
                },
                security = Shared.securityRequirements(op.path("security")),
                externalDocs = Shared.externalDocs(op.path("externalDocs")),
                servers = servers(op.path("servers").takeIf { !it.isMissingNode } ?: item.path("servers")),
            )
        }
    }

    private fun servers(node: JsonNode): List<ServerView> = node.takeIf { it.isArray }?.filter { it.isObject }?.map { s ->
        ServerView(
            url = Nodes.text(s, "url") ?: "",
            description = Nodes.text(s, "description"),
            variables = Nodes.fields(s.path("variables")).map { (name, v) ->
                ServerVariableView(name, Nodes.text(v, "default") ?: "", Nodes.strings(v, "enum"), Nodes.text(v, "description"))
            },
        )
    }.orEmpty()

    private fun parameters(node: JsonNode, pointer: String): List<ParameterView> =
        node.takeIf { it.isArray }?.mapIndexedNotNull { i, raw ->
            val p = Shared.deref(root, raw)
            val name = Nodes.text(p, "name") ?: return@mapIndexedNotNull null
            val location = Nodes.text(p, "in") ?: return@mapIndexedNotNull null
            val pPointer = Shared.pointerOf(raw, "$pointer/$i")
            ParameterView(
                pointer = pPointer,
                name = name,
                location = location,
                required = Nodes.bool(p, "required") || location == "path",
                deprecated = Nodes.bool(p, "deprecated"),
                description = Nodes.text(p, "description"),
                style = Nodes.text(p, "style"),
                explode = p.path("explode").takeIf { it.isBoolean }?.asBoolean(),
                schema = p.path("schema").takeIf { it.isObject || it.isBoolean }?.let { walker.node(it, "$pPointer/schema") },
                examples = Shared.examples(root, p),
                content = content(p.path("content"), "$pPointer/content"),
            )
        }.orEmpty()

    private fun requestBody(raw: JsonNode, pointer: String): RequestBodyView {
        val body = Shared.deref(root, raw)
        val bPointer = Shared.pointerOf(raw, pointer)
        val content = content(body.path("content"), "$bPointer/content")
        return RequestBodyView(bPointer, Nodes.text(body, "description"), Nodes.bool(body, "required"), content)
    }

    private fun response(status: String, raw: JsonNode, pointer: String): ResponseView {
        val r = Shared.deref(root, raw)
        val rPointer = Shared.pointerOf(raw, pointer)
        return ResponseView(
            pointer = rPointer,
            status = status,
            description = Nodes.text(r, "description"),
            headers = Nodes.fields(r.path("headers")).map { (name, h) ->
                val header = Shared.deref(root, h)
                val hPointer = Shared.pointerOf(h, "$rPointer/headers/${JsonPointers.escape(name)}")
                HeaderView(
                    name, Nodes.text(header, "description"), Nodes.bool(header, "required"), Nodes.bool(header, "deprecated"),
                    header.path("schema").takeIf { it.isObject || it.isBoolean }?.let { walker.node(it, "$hPointer/schema") },
                )
            },
            content = content(r.path("content"), "$rPointer/content"),
        )
    }

    private fun content(node: JsonNode, pointer: String): List<MediaTypeView> = Nodes.fields(node).map { (mediaType, m) ->
        val mPointer = "$pointer/${JsonPointers.escape(mediaType)}"
        MediaTypeView(
            mediaType,
            m.path("schema").takeIf { it.isObject || it.isBoolean }?.let { walker.node(it, "$mPointer/schema") },
            Shared.examples(root, m),
        )
    }

    companion object {
        private val METHODS = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")
    }
}
