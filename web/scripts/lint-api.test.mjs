import assert from "node:assert/strict";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { afterEach, test } from "node:test";
import { loadRules, main } from "./lint-api.mjs";

const temporaryDirectories = [];

afterEach(async () => {
  await Promise.all(temporaryDirectories.splice(0).map((directory) => rm(directory, { recursive: true })));
});

async function temporaryFile(name, content) {
  const directory = await mkdtemp(join(tmpdir(), "covenant-api-lint-"));
  temporaryDirectories.push(directory);
  const file = join(directory, name);
  await writeFile(file, content);
  return pathToFileURL(file);
}

const baseExtends = [["spectral:oas", "off"]];

test("rulesets require the exact expected extends declaration", async (t) => {
  await t.test("missing extends", async () => {
    const ruleset = await temporaryFile("missing.yaml", "rules: {}\n");
    await assert.rejects(loadRules(ruleset, baseExtends), /Unsupported ruleset extends/);
  });

  await t.test("unknown extends", async () => {
    const ruleset = await temporaryFile("unknown.yaml", "extends: spectral:asyncapi\nrules: {}\n");
    await assert.rejects(loadRules(ruleset, baseExtends), /Unsupported ruleset extends/);
  });
});

test("rulesets reject unknown top-level keys", async () => {
  const ruleset = await temporaryFile(
    "top-level.yaml",
    "extends: [[spectral:oas, off]]\nrules: {}\nformats: [oas3]\n",
  );
  await assert.rejects(loadRules(ruleset, baseExtends), /Unsupported ruleset key: formats/);
});

test("rulesets reject unknown custom functions", async () => {
  const ruleset = await temporaryFile(
    "function.yaml",
    "extends: [[spectral:oas, off]]\nrules:\n  custom:\n    given: $\n    then:\n      function: customFunction\n",
  );
  await assert.rejects(loadRules(ruleset, baseExtends), /Unsupported Spectral function: customFunction/);
});

test("checker rulesets cannot disable an unknown inherited rule", async () => {
  const ruleset = await temporaryFile(
    "checker.yaml",
    "extends: ./api-guidelines.spectral.yaml\nrules:\n  unknown-rule: off\n",
  );
  await assert.rejects(
    loadRules(ruleset, "./api-guidelines.spectral.yaml", {}),
    /Cannot disable unknown inherited rule: unknown-rule/,
  );
});

test("malformed YAML fails before linting", async () => {
  const ruleset = await temporaryFile("malformed.yaml", "extends: [[spectral:oas, off]\nrules: {}\n");
  await assert.rejects(loadRules(ruleset, baseExtends));
});

test("a real API rule error returns a nonzero status", async () => {
  const source = await readFile(
    new URL("../../server/src/main/resources/openapi/documentation.yaml", import.meta.url),
    "utf8",
  );
  const invalid = source.replace("  /api/v1/health:", "  /health:");
  assert.notEqual(invalid, source);
  const document = await temporaryFile("invalid-openapi.yaml", invalid);
  const output = [];

  assert.equal(await main([fileURLToPath(document)], (line) => output.push(line)), 1);
  assert.match(output.join("\n"), /error paths-are-versioned/);
});
