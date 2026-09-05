import { describe, expect, test } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import SchemaTree from "./SchemaTree";
import { node, OPENAPI } from "../test/readerFixtures";

describe("SchemaTree", () => {
  test("renders types, required stars, facts and markers; nested nodes open at the top level and collapse deeper", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SchemaTree node={OPENAPI.schemas[0].schema} schemaAnchors={new Map([["#/components/schemas/Owner", "schema-1"]])} />);
    expect(screen.getAllByText("object").length).toBeGreaterThan(0);
    expect(screen.getByText("id")).toBeInTheDocument();
    expect(screen.getAllByLabelText("required")).toHaveLength(2);
    expect(screen.getByText("(int64)")).toBeInTheDocument();
    expect(screen.getByText("read-only")).toBeInTheDocument();
    expect(screen.getByText("string | null")).toBeInTheDocument();
    expect(screen.getByText("minLength: 1")).toBeInTheDocument();
    expect(screen.getAllByText("dog")).toHaveLength(2);
    expect(screen.getByText("deprecated")).toBeInTheDocument();
    expect(screen.getByText("discriminated by kind")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Owner" })).toHaveAttribute("href", "#schema-1");
    expect(screen.getByText("unresolved: https://example.com/x.yaml#/Thing")).toBeInTheDocument();
    // The owner is nested one level down: collapsed until expanded.
    expect(screen.queryByText("recursive → A pet")).not.toBeInTheDocument();
    const expand = screen.getByRole("button", { name: "Expand owner" });
    expect(expand).toHaveAttribute("aria-expanded", "false");
    await user.click(expand);
    expect(screen.getByText("recursive → A pet")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Collapse owner" })).toHaveAttribute("aria-expanded", "true");
    await user.click(screen.getByRole("button", { name: "Expand tags" }));
    expect(screen.getByText("additional properties")).toBeInTheDocument();
  });

  test("tuple items, composition groups, raw payloads, truncation and the no-additional note", () => {
    const tree = node({
      pointer: "/s",
      types: ["array"],
      tupleItems: [node({ pointer: "/s/prefixItems/0", types: ["string"] }), node({ pointer: "/s/prefixItems/1", marker: "TRUNCATED" })],
      allOf: [node({ pointer: "/s/allOf/0", types: ["object"], properties: [{ name: "a", required: false, schema: node({ pointer: "/s/allOf/0/properties/a", types: ["string"] }) }], additionalPropertiesAllowed: false })],
      oneOf: [node({ pointer: "/s/oneOf/0", raw: "message X {}", format: "proto" })],
      not: node({ pointer: "/s/not", nullable: true }),
      constValue: "1",
      defaultValue: "[]",
      examples: ['["a"]'],
      title: "Tuple",
    });
    renderWithProviders(<SchemaTree node={tree} />);
    expect(screen.getByText("item 0")).toBeInTheDocument();
    expect(screen.getByText("cut")).toBeInTheDocument();
    expect(screen.getByText("allOf[0]")).toBeInTheDocument();
    expect(screen.getByText("oneOf[0]")).toBeInTheDocument();
    expect(screen.getByText("not a schema")).toBeInTheDocument();
    expect(screen.getByText("message X {}")).toBeInTheDocument();
    expect(screen.getByText("not")).toBeInTheDocument();
    expect(screen.getByText("any | null")).toBeInTheDocument();
    expect(screen.getByText("1", { selector: "code" })).toBeInTheDocument();
    expect(screen.getByText("[]", { selector: "code" })).toBeInTheDocument();
    expect(screen.getByText('["a"]', { selector: "code" })).toBeInTheDocument();
  });

  test("a long enum is cut with a count", () => {
    const tree = node({ pointer: "/e", types: ["string"], enumValues: Array.from({ length: 25 }, (_, i) => `v${i}`) });
    renderWithProviders(<SchemaTree node={tree} />);
    expect(screen.getByText("v19")).toBeInTheDocument();
    expect(screen.queryByText("v20")).not.toBeInTheDocument();
    expect(screen.getByText("+5 more")).toBeInTheDocument();
  });
});
