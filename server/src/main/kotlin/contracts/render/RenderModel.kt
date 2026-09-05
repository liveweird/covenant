package ch.nokillswit.contracts.render

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.Finding
import kotlinx.serialization.Serializable

/**
 * The reader's render model (milestone 4): a normalized, `$ref`-resolved, type-specific view of a
 * stored version, computed server-side per request (`GET …/{vid}/model`) so the SPA renders a typed
 * contract instead of parsing YAML. Lists are always present (never null); optional scalars are
 * omitted when null. Any JSON value that is not a string (enum members, defaults, examples) is
 * stringified compactly here — the spec carries no `{}`-typed fields.
 */
@Serializable
data class RenderModelResponse(
    val type: ContractType,
    val specVersion: String? = null,
    /** True when the render budget cut the walk somewhere (a `TRUNCATED` marker sits at the cut). */
    val truncated: Boolean,
    /** A stored document that no longer parses or fails the type gate — the model is then empty. */
    val error: Finding? = null,
    val openApi: OpenApiModel? = null,
    val asyncApi: AsyncApiModel? = null,
    val odcs: OdcsModel? = null,
)

@Serializable
enum class SchemaMarker { CIRCULAR, UNRESOLVED, TRUNCATED }

@Serializable
data class KeyValue(val key: String, val value: String)

@Serializable
data class Constraint(val keyword: String, val value: String)

@Serializable
data class PropertyView(val name: String, val required: Boolean, val schema: SchemaNode)

@Serializable
data class DiscriminatorView(val propertyName: String, val mapping: List<KeyValue>)

/** The shared recursive primitive — one shape for JSON Schema (2020-12 and OpenAPI 3.0's dialect) and Avro. */
@Serializable
data class SchemaNode(
    val pointer: String,
    /** The `$ref` this node came through (the last hop), when it did. */
    val ref: String? = null,
    val marker: SchemaMarker? = null,
    /** A non-schema payload (protobuf text, an unknown format), pretty-printed and capped. */
    val raw: String? = null,
    /** Never contains `"null"` — that becomes [nullable]; `object`/`array` inferred from keywords when absent. */
    val types: List<String>,
    val nullable: Boolean,
    val format: String? = null,
    val title: String? = null,
    val description: String? = null,
    val deprecated: Boolean,
    val readOnly: Boolean,
    val writeOnly: Boolean,
    val properties: List<PropertyView>,
    val additionalProperties: SchemaNode? = null,
    /** `additionalProperties: false` → false; a schema or true → true; absent → null. */
    val additionalPropertiesAllowed: Boolean? = null,
    val items: SchemaNode? = null,
    /** Positional item schemas — 2020-12 `prefixItems` and the older array-form `items`. */
    val tupleItems: List<SchemaNode>,
    val allOf: List<SchemaNode>,
    val anyOf: List<SchemaNode>,
    val oneOf: List<SchemaNode>,
    val not: SchemaNode? = null,
    val discriminator: DiscriminatorView? = null,
    val enumValues: List<String>,
    val constValue: String? = null,
    val defaultValue: String? = null,
    val examples: List<String>,
    val constraints: List<Constraint>,
)

// ---- shared ------------------------------------------------------------------------------------

@Serializable
data class ContactView(val name: String? = null, val url: String? = null, val email: String? = null)

@Serializable
data class LicenseView(val name: String, val url: String? = null, val identifier: String? = null)

@Serializable
data class ExternalDocsView(val url: String, val description: String? = null)

@Serializable
data class InfoView(
    val title: String,
    val version: String,
    val summary: String? = null,
    val description: String? = null,
    val termsOfService: String? = null,
    val contact: ContactView? = null,
    val license: LicenseView? = null,
)

@Serializable
data class TagView(val name: String, val description: String? = null, val externalDocs: ExternalDocsView? = null)

@Serializable
data class ExampleView(val name: String, val summary: String? = null, val description: String? = null, val value: String)

@Serializable
data class NamedSchemaView(val name: String, val pointer: String, val schema: SchemaNode)

@Serializable
data class OAuthFlowView(
    val type: String,
    val authorizationUrl: String? = null,
    val tokenUrl: String? = null,
    val refreshUrl: String? = null,
    val scopes: List<KeyValue>,
)

@Serializable
data class SecuritySchemeView(
    val name: String,
    val pointer: String,
    val type: String,
    val description: String? = null,
    val scheme: String? = null,
    val bearerFormat: String? = null,
    val location: String? = null,
    val paramName: String? = null,
    val openIdConnectUrl: String? = null,
    val flows: List<OAuthFlowView>,
)

/** One alternative of a security requirement: every scheme in it must hold. */
@Serializable
data class SecurityRequirementView(val schemes: List<SecuritySchemeUse>)

@Serializable
data class SecuritySchemeUse(val name: String, val scopes: List<String>)

// ---- OpenAPI ------------------------------------------------------------------------------------

@Serializable
data class ServerVariableView(val name: String, val default: String, val enumValues: List<String>, val description: String? = null)

@Serializable
data class ServerView(val url: String, val description: String? = null, val variables: List<ServerVariableView>)

@Serializable
data class MediaTypeView(val mediaType: String, val schema: SchemaNode? = null, val examples: List<ExampleView>)

@Serializable
data class ParameterView(
    val pointer: String,
    val name: String,
    val location: String,
    val required: Boolean,
    val deprecated: Boolean,
    val description: String? = null,
    val style: String? = null,
    val explode: Boolean? = null,
    val schema: SchemaNode? = null,
    val examples: List<ExampleView>,
    val content: List<MediaTypeView>,
)

@Serializable
data class RequestBodyView(val pointer: String, val description: String? = null, val required: Boolean, val content: List<MediaTypeView>)

@Serializable
data class HeaderView(
    val name: String,
    val description: String? = null,
    val required: Boolean,
    val deprecated: Boolean,
    val schema: SchemaNode? = null,
)

@Serializable
data class ResponseView(
    val pointer: String,
    val status: String,
    val description: String? = null,
    val headers: List<HeaderView>,
    val content: List<MediaTypeView>,
)

@Serializable
data class OperationView(
    val pointer: String,
    /** Upper-case. */
    val method: String,
    val path: String,
    val operationId: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val tags: List<String>,
    val deprecated: Boolean,
    val parameters: List<ParameterView>,
    val requestBody: RequestBodyView? = null,
    val responses: List<ResponseView>,
    /** Null = inherits the document's `security`; empty = explicitly none. */
    val security: List<SecurityRequirementView>? = null,
    val externalDocs: ExternalDocsView? = null,
    val servers: List<ServerView>,
)

@Serializable
data class OpenApiModel(
    val info: InfoView,
    val servers: List<ServerView>,
    val tags: List<TagView>,
    val operations: List<OperationView>,
    /** 3.1 `webhooks` — operations without a server-relative path (the key is the path field). */
    val webhooks: List<OperationView>,
    val security: List<SecurityRequirementView>,
    val securitySchemes: List<SecuritySchemeView>,
    val schemas: List<NamedSchemaView>,
    val externalDocs: ExternalDocsView? = null,
)

// ---- AsyncAPI -----------------------------------------------------------------------------------

@Serializable
data class AsyncServerView(
    val name: String,
    val host: String? = null,
    val pathname: String? = null,
    val protocol: String? = null,
    val protocolVersion: String? = null,
    val description: String? = null,
    val security: List<String>,
    val tags: List<String>,
    val bindings: List<String>,
)

@Serializable
data class ChannelParameterView(
    val name: String,
    val description: String? = null,
    val location: String? = null,
    val enumValues: List<String>,
    val default: String? = null,
    val schema: SchemaNode? = null,
)

/** A pointer to a rendered message by its key in [AsyncApiModel.messages]; `target` null when unresolved. */
@Serializable
data class MessageRefView(val name: String, val target: String? = null)

@Serializable
data class ChannelView(
    val pointer: String,
    val name: String,
    val address: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val servers: List<String>,
    val parameters: List<ChannelParameterView>,
    val messages: List<MessageRefView>,
    val bindings: List<String>,
)

@Serializable
data class AsyncOperationView(
    val pointer: String,
    val name: String,
    /** 3.x vocabulary: `send` or `receive` (2.x `publish` → `receive`, `subscribe` → `send`). */
    val action: String,
    /** The 2.x verb it was declared with, when it was. */
    val legacyAction: String? = null,
    val channel: String? = null,
    val messages: List<MessageRefView>,
    val reply: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val security: List<String>,
    val tags: List<String>,
    val bindings: List<String>,
)

@Serializable
data class MessageView(
    val key: String,
    val pointer: String,
    val name: String? = null,
    val title: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val contentType: String? = null,
    val schemaFormat: String? = null,
    val headers: SchemaNode? = null,
    val payload: SchemaNode? = null,
    val correlationId: String? = null,
    val examples: List<ExampleView>,
    val tags: List<String>,
    val bindings: List<String>,
    val deprecated: Boolean,
    /** Declared inline on a channel (or a 2.x operation) rather than under `components.messages`. */
    val inline: Boolean,
)

@Serializable
data class AsyncApiModel(
    val info: InfoView,
    val tags: List<TagView>,
    val defaultContentType: String? = null,
    val servers: List<AsyncServerView>,
    val channels: List<ChannelView>,
    val operations: List<AsyncOperationView>,
    val messages: List<MessageView>,
    val schemas: List<NamedSchemaView>,
    val securitySchemes: List<SecuritySchemeView>,
    val externalDocs: ExternalDocsView? = null,
)

// ---- ODCS ---------------------------------------------------------------------------------------

@Serializable
data class AuthoritativeDefinitionView(val type: String? = null, val url: String)

@Serializable
data class OdcsDescriptionView(
    val purpose: String? = null,
    val limitations: String? = null,
    val usage: String? = null,
    val authoritativeDefinitions: List<AuthoritativeDefinitionView>,
    val customProperties: List<KeyValue>,
)

@Serializable
data class QualityView(
    val pointer: String,
    val type: String? = null,
    val rule: String? = null,
    val name: String? = null,
    val description: String? = null,
    val query: String? = null,
    val engine: String? = null,
    val implementation: String? = null,
    val dimension: String? = null,
    val severity: String? = null,
    val businessImpact: String? = null,
    val schedule: String? = null,
    val scheduler: String? = null,
    /** Every `mustBe*` threshold, stringified. */
    val thresholds: List<KeyValue>,
)

@Serializable
data class OdcsPropertyView(
    val pointer: String,
    val name: String,
    val businessName: String? = null,
    val logicalType: String? = null,
    val physicalType: String? = null,
    val physicalName: String? = null,
    val description: String? = null,
    val required: Boolean,
    val unique: Boolean,
    val primaryKey: Boolean,
    val primaryKeyPosition: Int? = null,
    val partitioned: Boolean,
    val partitionKeyPosition: Int? = null,
    val classification: String? = null,
    val encryptedName: String? = null,
    val criticalDataElement: Boolean,
    val transformSourceObjects: List<String>,
    val transformLogic: String? = null,
    val transformDescription: String? = null,
    val examples: List<String>,
    val tags: List<String>,
    /** `logicalTypeOptions`, stringified. */
    val options: List<KeyValue>,
    val quality: List<QualityView>,
    val items: OdcsPropertyView? = null,
    val properties: List<OdcsPropertyView>,
    val customProperties: List<KeyValue>,
    val marker: SchemaMarker? = null,
)

@Serializable
data class DatasetView(
    val pointer: String,
    val name: String,
    val physicalName: String? = null,
    val physicalType: String? = null,
    val logicalType: String? = null,
    val businessName: String? = null,
    val description: String? = null,
    val dataGranularityDescription: String? = null,
    val tags: List<String>,
    val properties: List<OdcsPropertyView>,
    val quality: List<QualityView>,
    val authoritativeDefinitions: List<AuthoritativeDefinitionView>,
    val customProperties: List<KeyValue>,
)

@Serializable
data class OdcsRoleView(
    val role: String,
    val access: String? = null,
    val description: String? = null,
    val firstLevelApprovers: String? = null,
    val secondLevelApprovers: String? = null,
    val customProperties: List<KeyValue>,
)

@Serializable
data class OdcsServerView(
    val pointer: String,
    val server: String,
    val type: String? = null,
    val description: String? = null,
    val environment: String? = null,
    /** Every remaining scalar (host, port, database, schema, …), stringified. */
    val details: List<KeyValue>,
    val roles: List<OdcsRoleView>,
)

@Serializable
data class TeamMemberView(
    val username: String,
    val role: String? = null,
    val name: String? = null,
    val description: String? = null,
    val dateIn: String? = null,
    val dateOut: String? = null,
    val replacedByUsername: String? = null,
)

@Serializable
data class SlaPropertyView(
    val property: String,
    val value: String,
    val valueExt: String? = null,
    val unit: String? = null,
    val element: String? = null,
    val driver: String? = null,
)

@Serializable
data class SupportView(
    val channel: String,
    val url: String? = null,
    val tool: String? = null,
    val scope: String? = null,
    val description: String? = null,
    val invitationUrl: String? = null,
)

@Serializable
data class PriceView(val priceAmount: String? = null, val priceCurrency: String? = null, val priceUnit: String? = null)

@Serializable
data class OdcsModel(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val status: String? = null,
    val domain: String? = null,
    val dataProduct: String? = null,
    val tenant: String? = null,
    val tags: List<String>,
    val contractCreatedTs: String? = null,
    val description: OdcsDescriptionView? = null,
    val datasets: List<DatasetView>,
    val servers: List<OdcsServerView>,
    val team: List<TeamMemberView>,
    val roles: List<OdcsRoleView>,
    val slaDefaultElement: String? = null,
    val slaProperties: List<SlaPropertyView>,
    val support: List<SupportView>,
    val price: PriceView? = null,
    val authoritativeDefinitions: List<AuthoritativeDefinitionView>,
    val customProperties: List<KeyValue>,
)
