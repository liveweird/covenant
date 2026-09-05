import { describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "../test/render";
import FindingsPanel from "./FindingsPanel";
import { FINDING, SOFT_ERROR } from "../test/contractsFixtures";

const HARD = { severity: "ERROR" as const, source: "SYNTAX" as const, code: "YAML_PARSE", message: "bad indent", line: 4, column: 3 };
const INFO = { severity: "INFO" as const, source: "SYSTEM" as const, code: "FINDINGS_TRUNCATED", message: "12 more" };

describe("FindingsPanel", () => {
  test("counts by severity, lists every finding with its source/code/path and a jump button for positioned ones", async () => {
    const onJump = vi.fn();
    renderWithProviders(<FindingsPanel findings={[HARD, SOFT_ERROR, FINDING, INFO]} mode="stored" onJump={onJump} />);
    expect(screen.getByText("2 errors")).toBeInTheDocument();
    expect(screen.getByText("1 warnings")).toBeInTheDocument();
    expect(screen.getByText("1 notes")).toBeInTheDocument();
    const list = screen.getByRole("list", { name: "Findings" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(4);
    expect(within(list).getByText("info-contact")).toBeInTheDocument();
    expect(within(list).getByText("/info")).toBeInTheDocument();
    expect(within(list).getAllByRole("button", { name: /Go to line/ })).toHaveLength(3);
    await userEvent.setup().click(within(list).getByRole("button", { name: "Go to line 4, column 3" }));
    expect(onJump).toHaveBeenCalledWith(HARD);
  });

  test("in path mode the jump button targets the finding's element, only for findings with a path", async () => {
    const onJump = vi.fn();
    renderWithProviders(<FindingsPanel findings={[HARD, FINDING, INFO]} mode="stored" onJump={onJump} jumpBy="path" />);
    const list = screen.getByRole("list", { name: "Findings" });
    expect(within(list).queryAllByRole("button", { name: /Go to line/ })).toHaveLength(0);
    const buttons = within(list).getAllByRole("button", { name: "Go to the element" });
    expect(buttons).toHaveLength(1);
    await userEvent.setup().click(buttons[0]);
    expect(onJump).toHaveBeenCalledWith(FINDING);
  });

  test("severity and source chips narrow the list; no match says so", async () => {
    const user = userEvent.setup();
    renderWithProviders(<FindingsPanel findings={[HARD, FINDING]} mode="stored" />);
    await user.click(screen.getByRole("checkbox", { name: "Warning" }));
    const list = screen.getByRole("list", { name: "Findings" });
    expect(within(list).getAllByRole("listitem")).toHaveLength(1);
    expect(within(list).getByText("info-contact")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Syntax" }));
    expect(screen.getByText("No findings match the chips")).toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Note" })).toBeDisabled();
  });

  test("a live check that compared against an active version says so", () => {
    renderWithProviders(<FindingsPanel findings={[]} mode="live" baselineVersion="1.4.0" />);
    expect(screen.getByText("Compared against active version 1.4.0 for breaking changes")).toBeInTheDocument();
  });

  test("the empty states: waiting before the first live answer, then clean; an incomplete stored check is flagged", () => {
    const { rerender } = renderWithProviders(<FindingsPanel findings={[]} mode="live" checked={false} />);
    expect(screen.getByText("Checking the document…")).toBeInTheDocument();
    rerender(<FindingsPanel findings={[]} mode="live" checked />);
    expect(screen.getByText("No findings — the document passes every check")).toBeInTheDocument();
    rerender(<FindingsPanel findings={[]} mode="stored" checkComplete={false} />);
    expect(screen.getByText("Checks incomplete")).toBeInTheDocument();
  });
});
