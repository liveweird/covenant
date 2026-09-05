package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/** The view builders OpenAPI and AsyncAPI share: info, tags, external docs, examples, security schemes. */
internal object Shared {
    fun info(node: JsonNode): InfoView = InfoView(
        title = Nodes.text(node, "title") ?: "",
        version = Nodes.text(node, "version") ?: "",
        summary = Nodes.text(node, "summary"),
        description = Nodes.text(node, "description"),
        termsOfService = Nodes.text(node, "termsOfService"),
        contact = node.path("contact").takeIf { it.isObject }?.let {
            ContactView(Nodes.text(it, "name"), Nodes.text(it, "url"), Nodes.text(it, "email"))
        },
        license = node.path("license").takeIf { it.isObject }?.let {
            LicenseView(Nodes.text(it, "name") ?: "", Nodes.text(it, "url"), Nodes.text(it, "identifier"))
        },
    )

    fun externalDocs(node: JsonNode): ExternalDocsView? =
        node.takeIf { it.isObject }?.let { ExternalDocsView(Nodes.text(it, "url") ?: "", Nodes.text(it, "description")) }

    fun tags(node: JsonNode): List<TagView> = node.takeIf { it.isArray }?.filter { it.isObject }?.map {
        TagView(Nodes.text(it, "name") ?: "", Nodes.text(it, "description"), externalDocs(it.path("externalDocs")))
    }.orEmpty()

    fun tagNames(node: JsonNode): List<String> = when {
        node.isArray && node.all { it.isTextual } -> node.map { it.asText() }
        node.isArray -> node.mapNotNull { Nodes.text(it, "name") }
        else -> emptyList()
    }

    /** `examples` (a map of Example objects, `$ref`s followed one hop) + a bare `example` → rows. */
    fun examples(root: JsonNode, owner: JsonNode): List<ExampleView> {
        val named = Nodes.fields(owner.path("examples")).map { (name, raw) ->
            val ex = deref(root, raw)
            val value = ex.path("value").takeIf { !it.isMissingNode } ?: ex.path("externalValue")
            ExampleView(name, Nodes.text(ex, "summary"), Nodes.text(ex, "description"), Nodes.stringifyOrNull(value) ?: "")
        }
        val bare = owner.path("example").takeIf { !it.isMissingNode }?.let {
            listOf(ExampleView("example", null, null, Nodes.stringify(it)))
        }
        return bare.orEmpty() + named
    }

    fun securitySchemes(root: JsonNode, node: JsonNode, basePointer: String): List<SecuritySchemeView> =
        Nodes.fields(node).map { (name, raw) ->
            val s = deref(root, raw)
            SecuritySchemeView(
                name = name,
                pointer = "$basePointer/${JsonPointers.escape(name)}",
                type = Nodes.text(s, "type") ?: "",
                description = Nodes.text(s, "description"),
                scheme = Nodes.text(s, "scheme"),
                bearerFormat = Nodes.text(s, "bearerFormat"),
                location = Nodes.text(s, "in"),
                paramName = Nodes.text(s, "name"),
                openIdConnectUrl = Nodes.text(s, "openIdConnectUrl"),
                flows = Nodes.fields(s.path("flows")).map { (flow, f) ->
                    OAuthFlowView(
                        flow, Nodes.text(f, "authorizationUrl"), Nodes.text(f, "tokenUrl"), Nodes.text(f, "refreshUrl"),
                        scopes(f.path("scopes")),
                    )
                },
            )
        }

    /** OpenAPI's `scopes: {name: description}` and AsyncAPI 3's `scopes: [name]` — both as rows. */
    fun scopes(node: JsonNode): List<KeyValue> = when {
        node.isArray -> node.map { KeyValue(it.asText(), "") }
        else -> Nodes.keyValues(node)
    }

    fun securityRequirements(node: JsonNode): List<SecurityRequirementView>? = node.takeIf { it.isArray }?.map { req ->
        SecurityRequirementView(Nodes.fields(req).map { (name, scopes) -> SecuritySchemeUse(name, tagNames(scopes)) })
    }

    /** One `$ref` hop in-document; anything else (external, dangling) stays as the node itself. */
    fun deref(root: JsonNode, node: JsonNode): JsonNode {
        val ref = Nodes.text(node, "\$ref") ?: return node
        return JsonPointers.resolve(root, ref) ?: node
    }

    /** The pointer a `$ref`'d node lives at, else the pointer it was reached by. */
    fun pointerOf(node: JsonNode, fallback: String): String = Nodes.text(node, "\$ref")?.let { JsonPointers.pointerOf(it) } ?: fallback
}
