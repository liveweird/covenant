import { describe, expect, test } from "vitest";
import { isContentEditable, isDeletable, isIrreversibleTransition, LIFECYCLES, TRANSITIONS } from "./lifecycle";

describe("lifecycle", () => {
  test("the matrix mirrors the server: forward only, PROPOSED may step back, RETIRED is terminal", () => {
    expect(TRANSITIONS.DRAFT).toEqual(["PROPOSED"]);
    expect(TRANSITIONS.PROPOSED).toEqual(["DRAFT", "ACTIVE"]);
    expect(TRANSITIONS.ACTIVE).toEqual(["DEPRECATED"]);
    expect(TRANSITIONS.DEPRECATED).toEqual(["RETIRED"]);
    expect(TRANSITIONS.RETIRED).toEqual([]);
    expect(LIFECYCLES).toHaveLength(5);
  });

  test("editable and deletable follow the publication line", () => {
    expect(LIFECYCLES.filter(isContentEditable)).toEqual(["DRAFT", "PROPOSED"]);
    expect(LIFECYCLES.filter(isDeletable)).toEqual(["DRAFT"]);
    expect(LIFECYCLES.filter(isIrreversibleTransition)).toEqual(["DEPRECATED", "RETIRED"]);
  });
});
