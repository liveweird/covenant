import { describe, expect, test } from "vitest";
import { groupFindingsByCode, groupRowsByContract } from "./errorGroups";
import type { ErrorRow } from "../api/errors";
import type { Finding } from "../api/versions";

const OAS_PARSE: Finding = { severity: "ERROR", source: "SCHEMA", code: "OAS_PARSE", message: "paths is required", path: "/", line: 1, column: 1 };
const INFO_CONTACT: Finding = { severity: "WARN", source: "LINT", code: "info-contact", message: "Info object must have contact", path: "/info", line: 2, column: 1 };

const CONTRACT_A = { id: 5, name: "orders-api", type: "OPENAPI" as const, system: { id: 7, name: "gateway" }, domain: { id: 1, name: "Payments" }, owner: { kind: "TEAM" as const, id: 3, name: "Payments Team", deleted: false } };
const CONTRACT_B = { ...CONTRACT_A, id: 6, name: "refunds-api" };
const VERSION = { id: 11, version: "1.1.0", lifecycle: "DRAFT" as const, checkErrors: 1, checkWarnings: 1, checkInfos: 0, checkComplete: true, checkedAt: 1 };

function row(contract: typeof CONTRACT_A, versionId: number, findings: Finding[]): ErrorRow {
  return { contract, version: { ...VERSION, id: versionId }, findings };
}

describe("groupFindingsByCode", () => {
  test("collapses repeats of the same (severity, source, code) into one group with the first message", () => {
    const groups = groupFindingsByCode([OAS_PARSE, OAS_PARSE, INFO_CONTACT]);
    expect(groups).toEqual([
      { severity: "ERROR", source: "SCHEMA", code: "OAS_PARSE", count: 2, message: "paths is required" },
      { severity: "WARN", source: "LINT", code: "info-contact", count: 1, message: "Info object must have contact" },
    ]);
  });

  test("an empty finding list groups to nothing", () => {
    expect(groupFindingsByCode([])).toEqual([]);
  });
});

describe("groupRowsByContract", () => {
  test("consecutive rows of one contract collapse into one run; a different contract starts a new run", () => {
    const rows = [row(CONTRACT_A, 11, [OAS_PARSE]), row(CONTRACT_A, 10, [INFO_CONTACT]), row(CONTRACT_B, 20, [INFO_CONTACT])];
    const runs = groupRowsByContract(rows);
    expect(runs).toHaveLength(2);
    expect(runs[0].contract.id).toBe(5);
    expect(runs[0].rows).toHaveLength(2);
    expect(runs[1].contract.id).toBe(6);
    expect(runs[1].rows).toHaveLength(1);
  });

  test("the same contract split across a page boundary lands as two separate runs", () => {
    const rows = [row(CONTRACT_A, 11, [OAS_PARSE]), row(CONTRACT_B, 20, [INFO_CONTACT]), row(CONTRACT_A, 10, [INFO_CONTACT])];
    const runs = groupRowsByContract(rows);
    expect(runs.map((r) => r.contract.id)).toEqual([5, 6, 5]);
  });
});
