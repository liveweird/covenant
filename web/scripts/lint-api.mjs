import { readFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const require = createRequire(import.meta.url);
const { Document, Spectral } = require("@stoplight/spectral-core");
const spectralFunctions = require("@stoplight/spectral-functions");
const { Yaml } = require("@stoplight/spectral-parsers");
const { oas } = require("@stoplight/spectral-rulesets");
const YAML = require("yaml");

const baseRuleset = new URL("../../api-guidelines/api-guidelines.spectral.yaml", import.meta.url);
const checkerRuleset = new URL("../../api-guidelines/checker.spectral.yaml", import.meta.url);
const defaultProfiles = [
  {
    document: new URL("../../server/src/main/resources/openapi/documentation.yaml", import.meta.url),
    ruleset: baseRuleset,
  },
  {
    document: new URL("../../checker/openapi.yaml", import.meta.url),
    ruleset: checkerRuleset,
  },
  {
    document: new URL("../../api-guidelines/examples/conformant.yaml", import.meta.url),
    ruleset: baseRuleset,
  },
];
const allowedTopLevelKeys = new Set(["extends", "rules"]);
const allowedRuleKeys = new Set(["description", "message", "severity", "given", "then", "resolved"]);
const allowedThenKeys = new Set(["field", "function", "functionOptions"]);

function rejectUnknownKeys(value, allowed, context) {
  for (const key of Object.keys(value)) {
    if (!allowed.has(key)) throw new Error(`Unsupported ${context} key: ${key}`);
  }
}

function bindFunction(then) {
  if (Array.isArray(then)) return then.map(bindFunction);
  if (!then || typeof then !== "object") throw new Error("Every Spectral rule must define an object `then`");

  rejectUnknownKeys(then, allowedThenKeys, "Spectral then");
  const implementation = spectralFunctions[then.function];
  if (typeof implementation !== "function") {
    throw new Error(`Unsupported Spectral function: ${then.function}`);
  }
  return { ...then, function: implementation };
}

export async function loadRules(ruleset, expectedExtends, inherited = {}) {
  const parsed = YAML.parse(await readFile(ruleset, "utf8"));
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error(`Ruleset must be an object: ${fileURLToPath(ruleset)}`);
  }
  rejectUnknownKeys(parsed, allowedTopLevelKeys, "ruleset");
  if (JSON.stringify(parsed.extends) !== JSON.stringify(expectedExtends)) {
    throw new Error(`Unsupported ruleset extends in ${fileURLToPath(ruleset)}`);
  }
  if (!parsed.rules || typeof parsed.rules !== "object" || Array.isArray(parsed.rules)) {
    throw new Error(`Ruleset must define a rules object: ${fileURLToPath(ruleset)}`);
  }

  const rules = { ...inherited };
  for (const [name, rule] of Object.entries(parsed.rules ?? {})) {
    if (rule === "off") {
      if (!Object.hasOwn(rules, name)) throw new Error(`Cannot disable unknown inherited rule: ${name}`);
      delete rules[name];
    } else if (rule === true) {
      rules[name] = true;
    } else if (rule && typeof rule === "object" && !Array.isArray(rule)) {
      rejectUnknownKeys(rule, allowedRuleKeys, `Spectral rule ${name}`);
      rules[name] = { ...rule, then: bindFunction(rule.then) };
    } else {
      throw new Error(`Unsupported Spectral rule definition: ${name}`);
    }
  }
  return rules;
}

export async function main(args = [], log = console.log) {
  if (args.length > 1) throw new Error("Usage: node scripts/lint-api.mjs [document]");
  const profiles = args[0]
    ? [{ document: pathToFileURL(resolve(args[0])), ruleset: baseRuleset }]
    : defaultProfiles;
  const baseRules = await loadRules(baseRuleset, [["spectral:oas", "off"]]);
  let hasErrors = false;

  for (const profile of profiles) {
    const rules = profile.ruleset === baseRuleset
      ? baseRules
      : await loadRules(profile.ruleset, "./api-guidelines.spectral.yaml", baseRules);
    const spectral = new Spectral();
    spectral.setRuleset({ extends: [[oas, "off"]], rules });

    const source = fileURLToPath(profile.document);
    const results = await spectral.run(new Document(await readFile(profile.document, "utf8"), Yaml, source));
    for (const result of results) {
      const severity = ["error", "warning", "info", "hint"][result.severity] ?? "unknown";
      const location = `${result.range.start.line + 1}:${result.range.start.character + 1}`;
      log(`${source}:${location} ${severity} ${result.code} ${result.message}`);
      hasErrors ||= result.severity === 0;
    }
    log(`${source}: ${results.length} finding(s)`);
  }

  return hasErrors ? 1 : 0;
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  process.exitCode = await main(process.argv.slice(2));
}
