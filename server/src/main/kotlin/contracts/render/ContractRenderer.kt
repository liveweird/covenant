package ch.nokillswit.contracts.render

import ch.nokillswit.contracts.ContractType
import ch.nokillswit.contracts.checks.DocumentParser
import ch.nokillswit.contracts.checks.Metadata
import ch.nokillswit.contracts.checks.ParseOutcome
import kotlinx.serialization.json.Json

/**
 * The reader's entry point: parse → the type gate → the family renderer, under one [RenderBudget].
 * A stored document that no longer parses or fails the gate answers a model with `error` set —
 * the live-check precedent (HARD findings as findings); the store paths already reject them, so
 * only a legacy row reaches this.
 */
object ContractRenderer {
    /**
     * The model's wire encoding: optional scalars and sub-objects are OMITTED when absent (the spec
     * declares them optional, not nullable) — Ktor's default JSON would write explicit nulls.
     */
    val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    }

    fun render(type: ContractType, content: String, budget: RenderBudget = RenderBudget()): RenderModelResponse {
        val root = when (val parsed = DocumentParser.parse(content)) {
            is ParseOutcome.Failed -> return RenderModelResponse(type, truncated = false, error = parsed.finding)
            is ParseOutcome.Parsed -> parsed.root
        }
        DocumentParser.typeGate(type, root)?.let { return RenderModelResponse(type, truncated = false, error = it) }
        val specVersion = Metadata.extract(type, root).specVersion
        return when (type) {
            ContractType.OPENAPI -> {
                val model = OpenApiRenderer(root, budget).render()
                RenderModelResponse(type, specVersion, budget.truncated, openApi = model)
            }
            ContractType.ASYNCAPI -> {
                val model = AsyncApiRenderer(root, budget).render()
                RenderModelResponse(type, specVersion, budget.truncated, asyncApi = model)
            }
            ContractType.ODCS -> {
                val model = OdcsRenderer(root, budget).render()
                RenderModelResponse(type, specVersion, budget.truncated, odcs = model)
            }
        }
    }
}
