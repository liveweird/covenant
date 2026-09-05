import type { diff as diffType } from "@asyncapi/diff";
import * as asyncapiDiff from "@asyncapi/diff";
import type { Parser as ParserType } from "@asyncapi/parser";
import * as asyncapiParser from "@asyncapi/parser";
import { fromDiagnostic, type Finding } from "../findings.ts";
import { cjsExport } from "../interop.ts";

// CJS packages — see src/interop.ts for why the exports are picked at runtime.
const Parser = cjsExport<typeof ParserType>(asyncapiParser, "Parser");
const diff = cjsExport<typeof diffType>(asyncapiDiff, "diff");

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

type DiffConfig = NonNullable<Parameters<typeof diff>[2]>;
type Change = { action: "add" | "remove" | "edit"; path: string; before?: unknown; after?: unknown };

/**
 * `info.version` changes with EVERY new version — the standard's "breaking" verdict on its edit
 * would flag every candidate. Everything else keeps @asyncapi/diff's classification.
 */
const DIFF_CONFIG: DiffConfig = {
  override: { "/info/version": { add: "non-breaking", remove: "breaking", edit: "non-breaking" } },
  outputType: "json",
};

const CODE_DIFF_SKIPPED = "asyncapi-diff-skipped";

/**
 * The breaking-change pass (milestone 2): the ACTIVE version's text against the candidate's
 * through @asyncapi/diff, over the parser's dereferenced documents. Every breaking change is one
 * BREAKING fact (minted WARN — the JVM settles WARN vs INFO by the MAJOR bump and adds the gate);
 * a pair the differ cannot compare (an unparseable previous document, two AsyncAPI major
 * versions) answers ONE INFO `asyncapi-diff-skipped` instead of failing the check.
 */
export async function breakingAsyncApi(previous: string, current: string): Promise<Finding[]> {
  parser ??= new Parser();
  const [before, after] = await Promise.all([
    parser.parse(previous, { source: "previous.yaml" }),
    parser.parse(current, { source: "contract.yaml" }),
  ]);
  if (!before.document || !after.document) return [skipped("one of the documents does not parse as AsyncAPI")];
  let changes: unknown;
  try {
    changes = diff(before.document.json(), after.document.json(), DIFF_CONFIG).breaking();
  } catch (error) {
    // "diff between different AsyncAPI version is not allowed" — a 2.x baseline under a 3.x candidate.
    return [skipped(error instanceof Error ? error.message : String(error))];
  }
  if (!Array.isArray(changes)) return [];
  return (changes as Change[]).filter((c) => !c.path.includes("x-parser-")).map(toFinding);
}

const VERBS: Record<Change["action"], string> = { add: "Added", remove: "Removed", edit: "Changed" };

function toFinding(change: Change): Finding {
  const verb = VERBS[change.action];
  const scalar = (v: unknown) => v !== null && (typeof v === "string" || typeof v === "number" || typeof v === "boolean");
  const detail =
    change.action === "edit" && scalar(change.before) && scalar(change.after) ? ` (${clip(change.before)} → ${clip(change.after)})` : "";
  return {
    severity: "WARN",
    source: "BREAKING",
    code: `breaking-${change.action}`,
    message: `${verb} ${change.path}${detail}`,
    path: change.path,
  };
}

function clip(value: unknown): string {
  const text = String(value);
  return text.length > 40 ? `${text.slice(0, 37)}…` : text;
}

function skipped(reason: string): Finding {
  return {
    severity: "INFO",
    source: "BREAKING",
    code: CODE_DIFF_SKIPPED,
    message: `Breaking changes against the active version could not be computed — ${reason}`,
  };
}
