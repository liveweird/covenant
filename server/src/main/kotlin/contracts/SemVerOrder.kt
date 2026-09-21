package ch.nokillswit.contracts

import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.SortOrder

/** SQL and Kotlin use the same identifier precedence, including numeric parts beyond Long. */
private object PrereleaseOrder : Expression<List<String>>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        queryBuilder.append("covenant_semver_prerelease_key(")
        queryBuilder.append(ContractVersionService.ContractVersions.semverPrerelease)
        queryBuilder.append(") COLLATE \"C\"")
    }
}

/** Shared by paged version/error lists and exports; NULL denotes a stable release. */
internal fun semverOrder(descending: Boolean): List<Pair<Expression<*>, SortOrder>> {
    val versions = ContractVersionService.ContractVersions
    val order = if (descending) SortOrder.DESC else SortOrder.ASC
    val nulls = if (descending) SortOrder.DESC_NULLS_FIRST else SortOrder.ASC_NULLS_LAST
    return listOf(
        versions.semverMajor to order,
        versions.semverMinor to order,
        versions.semverPatch to order,
        PrereleaseOrder to nulls,
    )
}
