import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import OwnerSelect from "./OwnerSelect";
import { calledUrl, serve, signIn, type FetchMock } from "../test/contractsFixtures";

describe("OwnerSelect", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a regular user is offered their own teams and themselves", async () => {
    signIn([], 2);
    serve(mockFetch);
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<OwnerSelect value={null} onChange={onChange} />);
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Payments Team" })).toBeInTheDocument();
    expect(await screen.findByRole("option", { name: "Reg User (you)" })).toBeInTheDocument();
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/teams?") && u.includes("memberId=2"))).toBeDefined();
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/users?"))).toBeUndefined();
    await user.click(screen.getByRole("option", { name: "Reg User (you)" }));
    expect(onChange.mock.calls[0][0]).toBe("USER:2");
  });

  test("an admin sees every team and searches users server-side; a current owner outside the lists is injected", async () => {
    signIn(["ADMIN"], 1);
    serve(mockFetch);
    const user = userEvent.setup();
    renderWithProviders(<OwnerSelect value="USER:77" onChange={vi.fn()} current={{ value: "USER:77", label: "Gone Person" }} />);
    await waitFor(() => expect(screen.getByLabelText("Owner", { selector: "input" })).toHaveValue("Gone Person"));
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    expect(await screen.findByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Payments Team" })).toBeInTheDocument();
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/teams?") && !u.includes("memberId"))).toBeDefined();
  });
});
