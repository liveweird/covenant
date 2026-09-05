import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import TextDiffView from "./TextDiffView";

describe("TextDiffView", () => {
  test("prefixes rows with the −/+ signal in the same text node and renders the hidden-run marker", () => {
    renderWithProviders(
      <TextDiffView
        rows={[{ kind: "same", text: "a" }, { kind: "removed", text: "b" }, { kind: "added", text: "c" }, { kind: "skipped", count: 4 }]}
        label="Differences from 1.0.0 to 1.1.0"
      />,
    );
    const view = screen.getByRole("group", { name: "Differences from 1.0.0 to 1.1.0" });
    expect(view).toHaveAttribute("tabindex", "0");
    expect(screen.getByText("- b")).toBeInTheDocument();
    expect(screen.getByText("+ c")).toBeInTheDocument();
    expect(screen.getByText("4 unchanged lines hidden")).toBeInTheDocument();
  });
});
