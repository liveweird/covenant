import type { Parser as ParserType } from "@asyncapi/parser";
import * as asyncapiParser from "@asyncapi/parser";
import { fromDiagnostic, type Finding } from "../findings.ts";
import { cjsExport } from "../interop.ts";

// CJS package — see src/interop.ts for why the export is picked at runtime.
const Parser = cjsExport<typeof ParserType>(asyncapiParser, "Parser");

let parser: ParserType | undefined;

const AVRO_UNCHECKED = /no schema parser registered/i;

/**
 * The AsyncAPI semantic pass: the parser's own ruleset (schema + channel/operation/server/
 * security consistency). Runs ONLY after `externalRefFindings` came back empty — its resolver
 * would otherwise fetch (see src/refs.ts). Avro payloads produce a "no schema parser
 * registered" warning here; the JVM validates Avro with Apache Avro, so that one is dropped.
 */
export async function validateAsyncApi(content: string): Promise<Finding[]> {
  parser ??= new Parser();
  const diagnostics = await parser.validate(content, { source: "contract.yaml" });
  return diagnostics
    .filter((d) => !AVRO_UNCHECKED.test(d.message))
    .map((d) => fromDiagnostic(d, "SEMANTIC"));
}
