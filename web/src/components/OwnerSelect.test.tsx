import { useState } from "react";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import OwnerSelect from "./OwnerSelect";
import { calledUrl, serve, signIn, type FetchMock } from "../test/contractsFixtures";

function ControlledOwnerSelect() {
  const [value, setValue] = useState<string | null>("TEAM:3");
  return <OwnerSelect value={value} onChange={setValue} current={{ value: "TEAM:3", label: "Payments Team" }} />;
}

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
    const owner = screen.getByLabelText("Owner", { selector: "input" });
    await waitFor(() => expect(owner).toHaveValue("Gone Person"));
    await user.click(owner);
    expect(await screen.findByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();
    expect(screen.getByRole("option", { name: "Payments Team" })).toBeInTheDocument();
    expect(calledUrl(mockFetch, "GET", (u) => u.startsWith("/api/v1/teams?") && !u.includes("memberId"))).toBeDefined();
    await user.clear(owner);
    await user.type(owner, "Admin");
    await waitFor(() => expect(calledUrl(mockFetch, "GET", (u) => u.includes("name=Admin"))).toBeDefined());
  });

  test.each([{ roles: ["ADMIN"] }, { roles: [] }])("loads teams beyond page one with the caller membership scope: $roles", async ({ roles }) => {
    signIn(roles, 2);
    serve(mockFetch, {
      "GET /api/v1/teams?": (url) => {
        const page = Number(new URL(url, "http://localhost").searchParams.get("page"));
        const items = page === 1
          ? Array.from({ length: 100 }, (_, index) => ({ id: index + 1, name: `Team ${index + 1}` }))
          : [{ id: 101, name: "Late team" }];
        return { status: 200, body: { items, page, pageSize: 100, total: 101 } };
      },
    });
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<OwnerSelect value={null} onChange={onChange} />);
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Late team" }));
    expect(onChange).toHaveBeenCalledWith("TEAM:101");
    const calls = mockFetch.mock.calls.filter(([url]) => url.startsWith("/api/v1/teams?"));
    expect(calls).toHaveLength(2);
    for (const [url] of calls) expect(url.includes("memberId=2")).toBe(!roles.includes("ADMIN"));
  });

  test("a selected owner label does not become a server-side user search", async () => {
    signIn(["ADMIN"], 1);
    serve(mockFetch);
    const fetchRoute = mockFetch.getMockImplementation()!;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/users?") && url.includes("name=")) return new Promise<Response>(() => undefined);
      return fetchRoute(url, init);
    });
    const user = userEvent.setup();
    renderWithProviders(<OwnerSelect value="TEAM:3" onChange={vi.fn()} current={{ value: "TEAM:3", label: "Payments Team" }} />);
    const owner = screen.getByLabelText("Owner", { selector: "input" });
    await waitFor(() => expect(owner).toHaveValue("Payments Team"));
    await user.click(owner);
    expect(await screen.findByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();

    await new Promise((resolve) => setTimeout(resolve, 350));

    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/users?") && url.includes("name="))).toBeUndefined();
    expect(screen.getByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();
  });

  test("a loaded user's canonical name and email label does not become a search for the stored plain name", async () => {
    signIn(["ADMIN"], 1);
    serve(mockFetch);
    const fetchRoute = mockFetch.getMockImplementation()!;
    mockFetch.mockImplementation((url: string, init?: RequestInit) => {
      if (url.startsWith("/api/v1/users?") && url.includes("name=")) return new Promise<Response>(() => undefined);
      return fetchRoute(url, init);
    });
    const user = userEvent.setup();
    renderWithProviders(<OwnerSelect value="USER:1" onChange={vi.fn()} current={{ value: "USER:1", label: "Admin User" }} />);
    const owner = screen.getByLabelText("Owner", { selector: "input" });
    await waitFor(() => expect(owner).toHaveValue("Admin User (admin@covenant.local)"));
    await user.click(owner);
    expect(await screen.findByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();

    await new Promise((resolve) => setTimeout(resolve, 350));

    expect(calledUrl(mockFetch, "GET", (url) => url.startsWith("/api/v1/users?") && url.includes("name="))).toBeUndefined();
    expect(screen.getByRole("option", { name: "Admin User (admin@covenant.local)" })).toBeInTheDocument();
  });

  test("a remotely searched user remains selected when the unfiltered page does not contain them", async () => {
    signIn(["ADMIN"], 1);
    serve(mockFetch, {
      "GET /api/v1/users?": (url) => ({
        status: 200,
        body: url.includes("name=Remote")
          ? { items: [{ id: 99, name: "Remote User", email: "remote@example.com", roles: [], disabledFeatures: [], language: "en" }], page: 1, pageSize: 50, total: 1 }
          : { items: [{ id: 1, name: "Admin User", email: "admin@covenant.local", roles: ["ADMIN"], disabledFeatures: [], language: "en" }], page: 1, pageSize: 50, total: 1 },
      }),
    });
    const user = userEvent.setup();
    renderWithProviders(<ControlledOwnerSelect />);
    const owner = screen.getByLabelText("Owner", { selector: "input" });
    await waitFor(() => expect(owner).toHaveValue("Payments Team"));
    await user.clear(owner);
    await user.type(owner, "Remote");
    await user.click(await screen.findByRole("option", { name: "Remote User (remote@example.com)" }));
    await waitFor(() => expect(owner).toHaveValue("Remote User (remote@example.com)"));

    await new Promise((resolve) => setTimeout(resolve, 350));

    expect(owner).toHaveValue("Remote User (remote@example.com)");
    expect(calledUrl(mockFetch, "GET", (url) => url.includes("name=Remote"))).toBeDefined();
    expect(calledUrl(mockFetch, "GET", (url) => url.includes("name=Remote+User"))).toBeUndefined();
  });
});
