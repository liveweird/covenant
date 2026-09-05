package ch.nokillswit.contracts.render

import com.fasterxml.jackson.databind.JsonNode

/**
 * ODCS 3.x → [OdcsModel]: the header, datasets with nested properties (budget-bounded), quality at
 * both levels, servers with their roles, team, SLA, support, price and custom properties.
 */
class OdcsRenderer(private val root: JsonNode, private val budget: RenderBudget) {

    fun render(): OdcsModel = OdcsModel(
        id = Nodes.text(root, "id"),
        name = Nodes.text(root, "name"),
        version = Nodes.text(root, "version"),
        status = Nodes.text(root, "status"),
        domain = Nodes.text(root, "domain"),
        dataProduct = Nodes.text(root, "dataProduct"),
        tenant = Nodes.text(root, "tenant"),
        tags = Nodes.strings(root, "tags"),
        contractCreatedTs = Nodes.text(root, "contractCreatedTs"),
        description = root.path("description").takeIf { it.isObject }?.let { d ->
            OdcsDescriptionView(
                Nodes.text(d, "purpose"), Nodes.text(d, "limitations"), Nodes.text(d, "usage"),
                definitions(d), Nodes.keyValues(d.path("customProperties")),
            )
        },
        datasets = Nodes.objects(root, "schema").mapIndexed { i, d -> dataset(d, "/schema/$i") },
        servers = Nodes.objects(root, "servers").mapIndexed { i, s -> server(s, "/servers/$i") },
        team = Nodes.objects(root, "team").map { m ->
            TeamMemberView(
                Nodes.text(m, "username") ?: "", Nodes.text(m, "role"), Nodes.text(m, "name"), Nodes.text(m, "description"),
                Nodes.text(m, "dateIn"), Nodes.text(m, "dateOut"), Nodes.text(m, "replacedByUsername"),
            )
        },
        roles = Nodes.objects(root, "roles").map { role(it) },
        slaDefaultElement = Nodes.text(root, "slaDefaultElement"),
        slaProperties = Nodes.objects(root, "slaProperties").map { p ->
            SlaPropertyView(
                Nodes.text(p, "property") ?: "", Nodes.stringifyOrNull(p.path("value")) ?: "", Nodes.stringifyOrNull(p.path("valueExt")),
                Nodes.text(p, "unit"), Nodes.text(p, "element"), Nodes.text(p, "driver"),
            )
        },
        support = Nodes.objects(root, "support").map { s ->
            SupportView(
                Nodes.text(s, "channel") ?: "", Nodes.text(s, "url"), Nodes.text(s, "tool"), Nodes.text(s, "scope"),
                Nodes.text(s, "description"), Nodes.text(s, "invitationUrl"),
            )
        },
        price = root.path("price").takeIf { it.isObject }?.let {
            PriceView(Nodes.stringifyOrNull(it.path("priceAmount")), Nodes.text(it, "priceCurrency"), Nodes.text(it, "priceUnit"))
        },
        authoritativeDefinitions = definitions(root),
        customProperties = Nodes.keyValues(root.path("customProperties")),
    )

    private fun definitions(node: JsonNode): List<AuthoritativeDefinitionView> =
        Nodes.objects(node, "authoritativeDefinitions").map {
            AuthoritativeDefinitionView(Nodes.text(it, "type"), Nodes.text(it, "url") ?: "")
        }

    private fun dataset(d: JsonNode, pointer: String): DatasetView = DatasetView(
        pointer = pointer,
        name = Nodes.text(d, "name") ?: "",
        physicalName = Nodes.text(d, "physicalName"),
        physicalType = Nodes.text(d, "physicalType"),
        logicalType = Nodes.text(d, "logicalType"),
        businessName = Nodes.text(d, "businessName"),
        description = Nodes.text(d, "description"),
        dataGranularityDescription = Nodes.text(d, "dataGranularityDescription"),
        tags = Nodes.strings(d, "tags"),
        properties = properties(d, pointer, 0),
        quality = quality(d, pointer),
        authoritativeDefinitions = definitions(d),
        customProperties = Nodes.keyValues(d.path("customProperties")),
    )

    private fun properties(owner: JsonNode, pointer: String, depth: Int): List<OdcsPropertyView> =
        Nodes.objects(owner, "properties").mapIndexed { i, p -> property(p, "$pointer/properties/$i", depth) }

    private fun property(p: JsonNode, pointer: String, depth: Int): OdcsPropertyView {
        val within = budget.take(depth)
        val base = OdcsPropertyView(
            pointer = pointer,
            name = Nodes.text(p, "name") ?: "",
            businessName = Nodes.text(p, "businessName"),
            logicalType = Nodes.text(p, "logicalType"),
            physicalType = Nodes.text(p, "physicalType"),
            physicalName = Nodes.text(p, "physicalName"),
            description = Nodes.text(p, "description"),
            required = Nodes.bool(p, "required"),
            unique = Nodes.bool(p, "unique"),
            primaryKey = Nodes.bool(p, "primaryKey"),
            primaryKeyPosition = Nodes.int(p, "primaryKeyPosition"),
            partitioned = Nodes.bool(p, "partitioned"),
            partitionKeyPosition = Nodes.int(p, "partitionKeyPosition"),
            classification = Nodes.text(p, "classification"),
            encryptedName = Nodes.text(p, "encryptedName"),
            criticalDataElement = Nodes.bool(p, "criticalDataElement"),
            transformSourceObjects = Nodes.strings(p, "transformSourceObjects"),
            transformLogic = Nodes.text(p, "transformLogic"),
            transformDescription = Nodes.text(p, "transformDescription"),
            examples = p.path("examples").takeIf { it.isArray }?.map { Nodes.stringify(it) }.orEmpty(),
            tags = Nodes.strings(p, "tags"),
            options = Nodes.keyValues(p.path("logicalTypeOptions")),
            quality = quality(p, pointer),
            items = null,
            properties = emptyList(),
            customProperties = Nodes.keyValues(p.path("customProperties")),
        )
        if (!within) return base.copy(marker = SchemaMarker.TRUNCATED)
        return base.copy(
            items = p.path("items").takeIf { it.isObject }?.let { property(it, "$pointer/items", depth + 1) },
            properties = properties(p, pointer, depth + 1),
        )
    }

    private fun quality(owner: JsonNode, pointer: String): List<QualityView> = Nodes.objects(owner, "quality").mapIndexed { i, q ->
        QualityView(
            pointer = "$pointer/quality/$i",
            type = Nodes.text(q, "type"),
            rule = Nodes.text(q, "rule"),
            name = Nodes.text(q, "name"),
            description = Nodes.text(q, "description"),
            query = Nodes.text(q, "query"),
            engine = Nodes.text(q, "engine"),
            implementation = Nodes.stringifyOrNull(q.path("implementation")),
            dimension = Nodes.text(q, "dimension"),
            severity = Nodes.text(q, "severity"),
            businessImpact = Nodes.text(q, "businessImpact"),
            schedule = Nodes.text(q, "schedule"),
            scheduler = Nodes.text(q, "scheduler"),
            thresholds = Nodes.fields(q).filter { (k, _) -> k.startsWith("mustBe") }.map { (k, v) -> KeyValue(k, Nodes.stringify(v)) },
        )
    }

    private fun server(s: JsonNode, pointer: String): OdcsServerView = OdcsServerView(
        pointer = pointer,
        server = Nodes.text(s, "server") ?: "",
        type = Nodes.text(s, "type"),
        description = Nodes.text(s, "description"),
        environment = Nodes.text(s, "environment"),
        details = Nodes.fields(s).filter { (k, v) -> k !in SERVER_HEAD && v.isValueNode }.map { (k, v) -> KeyValue(k, Nodes.stringify(v)) },
        roles = Nodes.objects(s, "roles").map { role(it) },
    )

    private fun role(r: JsonNode) = OdcsRoleView(
        Nodes.text(r, "role") ?: "", Nodes.text(r, "access"), Nodes.text(r, "description"),
        Nodes.text(r, "firstLevelApprovers"), Nodes.text(r, "secondLevelApprovers"), Nodes.keyValues(r.path("customProperties")),
    )

    companion object {
        private val SERVER_HEAD = setOf("server", "type", "description", "environment", "roles", "customProperties")
    }
}
