import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import VersionViewToggle from "./VersionViewToggle";

describe("VersionViewToggle", () => {
  test("offers Reader and Source, reports the pick, and can be disabled", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<VersionViewToggle view="source" onChange={onChange} />);
    expect(screen.getByRole("radiogroup", { name: "Document view" })).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "Reader" }));
    expect(onChange).toHaveBeenCalledWith("reader");
    rerender(<VersionViewToggle view="reader" onChange={onChange} disabled />);
    expect(screen.getByRole("radio", { name: "Reader" })).toBeChecked();
    expect(screen.getByRole("radio", { name: "Source" })).toBeDisabled();
  });
});
