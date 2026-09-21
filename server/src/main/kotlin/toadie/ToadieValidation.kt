package ch.nokillswit.toadie

import io.ktor.server.plugins.BadRequestException
import java.net.URI
import java.net.InetAddress

private val TOADIE_IDENTIFIER = Regex("^[A-Za-z0-9@_.:/=\\-]{1,100}$")

fun sanitizeToadieRequest(request: ToadieConnectionRequest): ToadieConnectionRequest = request.copy(
    name = request.name.trim(),
    baseUrl = request.baseUrl.trim().trimEnd('/'),
    browserUrl = request.browserUrl.trim().trimEnd('/'),
    apiKey = request.apiKey?.trim()?.takeIf { it.isNotEmpty() },
    mapping = request.mapping.copy(
        serviceBlueprint = request.mapping.serviceBlueprint.trim(),
        apiBlueprint = request.mapping.apiBlueprint.trim(),
        providesRelation = request.mapping.providesRelation.trim(),
        consumesRelation = request.mapping.consumesRelation.trim(),
        systemRelation = request.mapping.systemRelation.trim(),
    ),
    registryMapping = request.registryMapping?.let { mapping ->
        mapping.copy(
            domainBlueprint = mapping.domainBlueprint.trim(),
            systemDomainRelation = mapping.systemDomainRelation?.trim()?.takeIf(String::isNotEmpty),
            domainParentRelation = mapping.domainParentRelation?.trim()?.takeIf(String::isNotEmpty),
            domainDescriptionProperty = mapping.domainDescriptionProperty?.trim()?.takeIf(String::isNotEmpty),
            systemDescriptionProperty = mapping.systemDescriptionProperty?.trim()?.takeIf(String::isNotEmpty),
            teamDescriptionProperty = mapping.teamDescriptionProperty?.trim()?.takeIf(String::isNotEmpty),
        )
    },
)

fun validateToadieRequest(request: ToadieConnectionRequest, apiKeyRequired: Boolean, allowHttp: Boolean) {
    if (request.name.isBlank() || request.name.length > MAX_TOADIE_NAME_LENGTH || request.name.any(Char::isISOControl)) {
        throw BadRequestException("name must contain 1 to $MAX_TOADIE_NAME_LENGTH printable characters")
    }
    validateEndpoint(request.baseUrl, "baseUrl", allowHttp)
    validateEndpoint(request.browserUrl, "browserUrl", allowHttp)
    if (apiKeyRequired && request.apiKey == null) throw BadRequestException("apiKey is required")
    if ((request.apiKey?.length ?: 0) > 1000) throw BadRequestException("apiKey is too long")
    if (request.refreshIntervalMinutes !in 1..10_080) {
        throw BadRequestException("refreshIntervalMinutes must be between 1 and 10080")
    }
    val values = with(request.mapping) {
        listOf(serviceBlueprint, apiBlueprint, providesRelation, consumesRelation, systemRelation)
    }
    if (values.any { !TOADIE_IDENTIFIER.matches(it) }) {
        throw BadRequestException("mapping values contain unsupported characters")
    }
    request.registryMapping?.let { registry ->
        if (!registry.flattenDomains) throw BadRequestException("registryMapping.flattenDomains must be true")
        val registryValues = listOfNotNull(
            registry.domainBlueprint, registry.systemDomainRelation, registry.domainParentRelation,
            registry.domainDescriptionProperty, registry.systemDescriptionProperty, registry.teamDescriptionProperty,
        )
        if (registryValues.any { !TOADIE_IDENTIFIER.matches(it) }) {
            throw BadRequestException("registryMapping values contain unsupported characters")
        }
        val reserved = setOf(
            request.mapping.serviceBlueprint.lowercase(), request.mapping.apiBlueprint.lowercase(), "_team",
        )
        if (registry.domainBlueprint.lowercase() in reserved) {
            throw BadRequestException("registryMapping.domainBlueprint must identify a distinct blueprint")
        }
    }
}

private fun validateEndpoint(raw: String, field: String, allowHttp: Boolean) {
    if (raw.length > MAX_TOADIE_URL_LENGTH) throw BadRequestException("$field is too long")
    val uri = runCatching { URI(raw) }.getOrElse { throw BadRequestException("$field must be an absolute HTTP URL") }
    val allowedSchemes = if (allowHttp) setOf("http", "https") else setOf("https")
    val validAuthority = uri.isAbsolute && uri.scheme?.lowercase() in allowedSchemes && !uri.host.isNullOrBlank()
    val validSuffix = uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null
    if (!validAuthority || !validSuffix || isLinkLocalLiteral(uri.host)) {
        val schemes = if (allowHttp) "HTTP or HTTPS" else "HTTPS"
        throw BadRequestException(
            "$field must be an absolute $schemes URL without credentials, query, or fragment",
        )
    }
}

private fun isLinkLocalLiteral(host: String?): Boolean {
    val raw = host?.removePrefix("[")?.removeSuffix("]") ?: return false
    // InetAddress understands legacy one-to-four-part and integer IPv4 notation. Resolve only
    // syntactically numeric hosts so validation never performs DNS for an ADMIN-supplied name.
    val isLiteral = ':' in raw || raw.matches(Regex("^\\d+(?:\\.\\d+){0,3}$"))
    return isLiteral && runCatching { InetAddress.getByName(raw).isLinkLocalAddress }.getOrDefault(false)
}
