import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import SaveAnywayModal from "./SaveAnywayModal";
import { SOFT_ERROR } from "../test/contractsFixtures";

describe("SaveAnywayModal", () => {
  test("stays closed on null and lists the findings (capped at ten) when open", async () => {
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    const { rerender } = renderWithProviders(<SaveAnywayModal findings={null} onCancel={onCancel} onConfirm={onConfirm} saving={false} />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    const many = Array.from({ length: 12 }, (_, i) => ({ ...SOFT_ERROR, code: `E${i}` }));
    rerender(<SaveAnywayModal findings={many} onCancel={onCancel} onConfirm={onConfirm} saving={false} />);
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByText(/found 12 blocking finding/)).toBeInTheDocument();
    expect(screen.getByText("E9")).toBeInTheDocument();
    expect(screen.queryByText("E10")).not.toBeInTheDocument();
    expect(screen.getByText("…and 2 more")).toBeInTheDocument();
    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Save anyway" }));
    expect(onConfirm).toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onCancel).toHaveBeenCalled();
  });
});
