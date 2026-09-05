import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "../test/render";
import KeyValueEditor from "./KeyValueEditor";
import { rowsToRecord } from "../utils/tryIt";

describe("KeyValueEditor", () => {
  test("adds, edits and removes rows; blank names are dropped when the record is built", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { rerender } = renderWithProviders(<KeyValueEditor label="Headers" rows={[]} onChange={onChange} hint="Sent once." />);
    expect(screen.getByText("None")).toBeInTheDocument();
    expect(screen.getByText("Sent once.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add" }));
    expect(onChange).toHaveBeenLastCalledWith([{ key: "", value: "" }]);
    rerender(<KeyValueEditor label="Headers" rows={[{ key: "", value: "" }, { key: "X-A", value: "1" }]} onChange={onChange} />);
    await user.type(screen.getByLabelText("Headers: Name 1"), "Q");
    expect(onChange).toHaveBeenLastCalledWith([{ key: "Q", value: "" }, { key: "X-A", value: "1" }]);
    await user.click(screen.getByRole("button", { name: "Remove X-A" }));
    expect(onChange).toHaveBeenLastCalledWith([{ key: "", value: "" }]);
    expect(rowsToRecord([{ key: " ", value: "x" }, { key: " X-A ", value: "1" }])).toEqual({ "X-A": "1" });
  });
});
