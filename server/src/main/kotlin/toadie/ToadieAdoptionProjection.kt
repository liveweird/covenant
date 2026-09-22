package ch.nokillswit.toadie

import ch.nokillswit.infra.paging.PageRequest

internal fun adoptionPage(
    projection: ToadieFullUsageProjection,
    query: String?,
    paging: PageRequest,
): ToadieAdoptionResponse {
    var rows = projection.adoptions.items.asSequence()
    query?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
        val queryValue = foldSearch(raw)
        rows = rows.filter { row ->
            listOf(
                row.identifier, row.title, row.consumer.identifier, row.consumer.title,
                row.target.identifier, row.target.title,
            ).any { queryValue in foldSearch(it) }
        }
    }
    val comparator = paging.sort.map { field ->
        val ascending = when (field.name) {
            "title" -> compareBy<ToadieAdoptionRow> { it.title.lowercase() }
            else -> compareBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }
        }
        if (field.descending) ascending.reversed() else ascending
    }.reduce(Comparator<ToadieAdoptionRow>::thenComparing)
    val ordered = rows.sortedWith(comparator).toList()
    val offset = (paging.page.toLong() - 1L) * paging.pageSize.toLong()
    val pageItems = if (offset >= ordered.size) emptyList() else ordered.drop(offset.toInt()).take(paging.pageSize)
    return ToadieAdoptionResponse(
        pageItems, paging.page, paging.pageSize, ordered.size.toLong(), projection.connection,
        projection.cache, projection.adoptions.availability,
    )
}

internal fun projectAdoptions(
    availability: ToadieAdoptionAvailability,
    adoptionMapping: ToadieAdoptionMapping?,
    usageMapping: ToadieMapping,
    browserUrl: String,
    environmentBlueprint: String?,
    entities: List<ToadieEntitySnapshot>,
    linkedTargetIdentifiers: Set<String>,
): ToadieAdoptionsProjection {
    if (availability != ToadieAdoptionAvailability.AVAILABLE || adoptionMapping == null) {
        return ToadieAdoptionsProjection(availability, emptyList())
    }
    val indexed = entities.groupBy { it.blueprint.lowercase() }
        .mapValues { (_, rows) -> rows.associateBy { it.identifier } }
    val consumers = indexed[usageMapping.serviceBlueprint.lowercase()].orEmpty()
    val targets = indexed[usageMapping.apiBlueprint.lowercase()].orEmpty()
    val rows = entities.asSequence()
        .filter { it.blueprint.equals(adoptionMapping.blueprint, ignoreCase = true) }
        .filter { it.relations[adoptionMapping.targetRelation].orEmpty().single() in linkedTargetIdentifiers }
        .map { adoption -> adoptionRow(
            adoption, adoptionMapping, usageMapping, browserUrl, environmentBlueprint, indexed, consumers, targets,
        ) }
        .sortedBy { it.id.toULongOrNull() ?: ULong.MAX_VALUE }
        .toList()
    return ToadieAdoptionsProjection(availability, rows)
}

private fun adoptionRow(
    adoption: ToadieEntitySnapshot,
    mapping: ToadieAdoptionMapping,
    usageMapping: ToadieMapping,
    browserUrl: String,
    environmentBlueprint: String?,
    indexed: Map<String, Map<String, ToadieEntitySnapshot>>,
    consumers: Map<String, ToadieEntitySnapshot>,
    targets: Map<String, ToadieEntitySnapshot>,
): ToadieAdoptionRow {
    val consumer = checkNotNull(consumers[adoption.relations.getValue(mapping.consumerRelation).single()])
    val target = checkNotNull(targets[adoption.relations.getValue(mapping.targetRelation).single()])
    val environment = mapping.environmentRelation?.let { relation ->
        adoption.relations[relation].orEmpty().singleOrNull()?.let { identifier ->
            indexed[environmentBlueprint?.lowercase()].orEmpty()[identifier]
        }
    }
    return ToadieAdoptionRow(
        adoption.id, adoption.identifier, adoption.title, entityUrl(browserUrl, adoption.id),
        consumer.toRef(browserUrl), target.toRef(browserUrl), environment?.toRef(browserUrl),
        when {
            mapping.environmentRelation == null -> ToadieAdoptionEnvironmentScope.UNKNOWN
            environment == null -> ToadieAdoptionEnvironmentScope.ALL
            else -> ToadieAdoptionEnvironmentScope.SPECIFIC
        },
        mapping.kind, adoption.scalarProperties[mapping.valueProperty],
        mapping.statusProperty?.let(adoption.scalarProperties::get),
        mapping.declaredByProperty?.let(adoption.scalarProperties::get),
        mapping.verifiedAtProperty?.let(adoption.scalarProperties::get)?.let(::parseVerifiedAt),
        mapping.notesProperty?.let(adoption.scalarProperties::get),
        target.identifier in consumer.relations[usageMapping.consumesRelation].orEmpty(),
    )
}
