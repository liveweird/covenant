import type { Document as DocumentType, RulesetDefinition, Spectral as SpectralType } from "@stoplight/spectral-core";
import * as spectralCore from "@stoplight/spectral-core";
import * as spectralParsers from "@stoplight/spectral-parsers";
import * as spectralRefResolver from "@stoplight/spectral-ref-resolver";
import * as spectralRulesets from "@stoplight/spectral-rulesets";
import { fromDiagnostic, type Finding } from "../findings.ts";
import { cjsExport } from "../interop.ts";

// CJS packages — see src/interop.ts for why the exports are picked at runtime.
const Spectral = cjsExport<typeof SpectralType>(spectralCore, "Spectral");
const Document = cjsExport<typeof DocumentType>(spectralCore, "Document");
const Yaml = cjsExport<typeof import("@stoplight/spectral-parsers").Yaml>(spectralParsers, "Yaml");
const Resolver = cjsExport<typeof import("@stoplight/spectral-ref-resolver").Resolver>(spectralRefResolver, "Resolver");
const oas = cjsExport<RulesetDefinition>(spectralRulesets, "oas");
const asyncapi = cjsExport<RulesetDefinition>(spectralRulesets, "asyncapi");
type Spectral = SpectralType;

/** No readers at all: an external `$ref` becomes an `invalid-ref` finding, never an I/O. */
function offlineResolver() {
  return new Resolver({ resolvers: {} });
}

function makeSpectral(ruleset: RulesetDefinition): Spectral {
  const spectral = new Spectral({ resolver: offlineResolver() });
  spectral.setRuleset({ extends: [ruleset] } as RulesetDefinition);
  return spectral;
}

let oasSpectral: Spectral | undefined;
let asyncSpectral: Spectral | undefined;

export async function lintOpenApi(content: string): Promise<Finding[]> {
  oasSpectral ??= makeSpectral(oas);
  return run(oasSpectral, content);
}

export async function lintAsyncApi(content: string): Promise<Finding[]> {
  asyncSpectral ??= makeSpectral(asyncapi);
  return run(asyncSpectral, content);
}

async function run(spectral: Spectral, content: string): Promise<Finding[]> {
  // The Yaml parser reads JSON too (JSON is YAML); the name only labels the source.
  const document = new Document(content, Yaml, "contract.yaml");
  const results = await spectral.run(document);
  return results.map((result) => fromDiagnostic(result, "LINT"));
}
