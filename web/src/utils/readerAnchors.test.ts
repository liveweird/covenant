import { describe, expect, test } from "vitest";
import { schemaAnchorId, schemaAnchors } from "./readerAnchors";
import { OPENAPI } from "../test/readerFixtures";

describe("readerAnchors", () => {
  test("every named schema gets an index anchor keyed by its ref", () => {
    expect(schemaAnchorId(3)).toBe("schema-3");
    const anchors = schemaAnchors(OPENAPI.schemas);
    expect(anchors.get("#/components/schemas/Pet")).toBe("schema-0");
    expect(anchors.get("#/components/schemas/Owner")).toBe("schema-1");
    expect(anchors.get("#/components/schemas/Nope")).toBeUndefined();
  });
});
