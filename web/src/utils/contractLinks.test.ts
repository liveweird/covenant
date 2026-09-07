import { describe, expect, test } from "vitest";
import { contractPath, editContractPath, inferContractPath, inferVersionPath, newVersionPath, versionDiffPath, versionPath } from "./contractLinks";

describe("contractLinks", () => {
  test("spells the route family once", () => {
    expect(contractPath(5)).toBe("/contracts/5");
    expect(editContractPath(5)).toBe("/contracts/5/edit");
    expect(versionPath(5, 11)).toBe("/contracts/5/versions/11");
    expect(newVersionPath(5)).toBe("/contracts/5/versions/new");
    expect(newVersionPath(5, 11)).toBe("/contracts/5/versions/new?from=11");
    expect(versionDiffPath(5)).toBe("/contracts/5/diff");
    expect(versionDiffPath(5, 10, 11)).toBe("/contracts/5/diff?from=10&to=11");
    expect(versionDiffPath(5, undefined, 11)).toBe("/contracts/5/diff?to=11");
    expect(inferContractPath).toBe("/contracts/infer");
    expect(inferVersionPath(5)).toBe("/contracts/5/infer");
  });
});
