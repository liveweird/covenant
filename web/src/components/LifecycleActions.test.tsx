import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import LifecycleActions from "./LifecycleActions";

describe("LifecycleActions", () => {
  test("offers exactly the allowed moves and fires a forward step directly", async () => {
    const onTransition = vi.fn();
    const { rerender } = renderWithProviders(<LifecycleActions lifecycle="DRAFT" version="1.0.0" onTransition={onTransition} />);
    expect(screen.getAllByRole("button")).toHaveLength(1);
    await userEvent.setup().click(screen.getByRole("button", { name: "Propose" }));
    expect(onTransition).toHaveBeenCalledWith("PROPOSED");
    rerender(<LifecycleActions lifecycle="PROPOSED" version="1.0.0" onTransition={onTransition} />);
    expect(screen.getByRole("button", { name: "Back to draft" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Activate" })).toBeInTheDocument();
  });

  test("deprecate and retire confirm first; cancel fires nothing", async () => {
    const onTransition = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<LifecycleActions lifecycle="ACTIVE" version="1.0.0" onTransition={onTransition} />);
    await user.click(screen.getByRole("button", { name: "Deprecate" }));
    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText(/Version 1.0.0 will be marked deprecated/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect(onTransition).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Deprecate" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Deprecate" }));
    expect(onTransition).toHaveBeenCalledWith("DEPRECATED");
  });

  test("RETIRED renders nothing; disabled blocks every move", () => {
    const { rerender } = renderWithProviders(<LifecycleActions lifecycle="RETIRED" version="1.0.0" onTransition={vi.fn()} />);
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    rerender(<LifecycleActions lifecycle="DEPRECATED" version="1.0.0" disabled onTransition={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Retire" })).toBeDisabled();
  });
});
