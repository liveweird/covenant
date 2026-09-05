import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "../test/render";
import { Route, Routes } from "react-router-dom";
import EditContract from "./EditContract";
import { bodyOf, CONTRACT, findCall, serve, signIn, type FetchMock } from "../test/contractsFixtures";

function renderPage() {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/:id/edit" element={<EditContract />} />
      <Route path="/contracts/:id" element={<h2>Contract page</h2>} />
    </Routes>,
    { route: "/contracts/5/edit" },
  );
}

describe("EditContract page", () => {
  let mockFetch: FetchMock;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("prefills, locks system and type, PUTs the record and — for an admin's owner change — the transfer", async () => {
    serve(mockFetch, {
      "GET /api/v1/contracts/5": { status: 200, body: CONTRACT },
      "PUT /api/v1/contracts/5": { status: 204 },
      "PUT /api/v1/contracts/5/owner": { status: 204 },
    });
    const user = userEvent.setup();
    renderPage();
    expect(await screen.findByRole("heading", { level: 2, name: "Edit orders-api" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue("orders-api"));
    expect(screen.getByLabelText("System", { selector: "input" })).toBeDisabled();
    expect(screen.getByLabelText("Type", { selector: "input" })).toBeDisabled();
    expect(screen.getByText("Picking another owner transfers the contract")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Name"), "-v2");
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Admin User (admin@covenant.local)" }));
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/owner")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5"))).toEqual({ name: "orders-api-v2", description: "Orders" });
    expect(bodyOf(findCall(mockFetch, "PUT", "/api/v1/contracts/5/owner"))).toEqual({ ownerTeamId: null, ownerUserId: 1 });
    expect(await screen.findByRole("heading", { level: 2, name: "Contract page" })).toBeInTheDocument();
  });

  test("a non-admin writer cannot touch the owner and sends no transfer", async () => {
    signIn([], 2);
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 200, body: CONTRACT }, "PUT /api/v1/contracts/5": { status: 204 } });
    const user = userEvent.setup();
    renderPage();
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue("orders-api"));
    expect(screen.getByLabelText("Owner", { selector: "input" })).toBeDisabled();
    expect(screen.getByText("Only an administrator can transfer ownership")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await waitFor(() => expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5")).toBeDefined());
    expect(findCall(mockFetch, "PUT", "/api/v1/contracts/5/owner")).toBeUndefined();
  });

  test("a reader is turned away; a missing contract says so", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 200, body: { ...CONTRACT, canWrite: false } } });
    const { unmount } = renderPage();
    expect(await screen.findByText(/You cannot edit this contract/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the contract" })).toHaveAttribute("href", "/contracts/5");
    unmount();
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 404, body: { title: "Not Found", status: 404 } } });
    renderPage();
    expect(await screen.findByText("This contract does not exist (or was deleted).")).toBeInTheDocument();
  });

  test("a 409 on the name marks the field", async () => {
    serve(mockFetch, { "GET /api/v1/contracts/5": { status: 200, body: CONTRACT }, "PUT /api/v1/contracts/5": { status: 409, body: { title: "Conflict", status: 409 } } });
    renderPage();
    await waitFor(() => expect(screen.getByLabelText("Name")).toHaveValue("orders-api"));
    await userEvent.setup().click(screen.getByRole("button", { name: /^save$/i }));
    expect(await screen.findByText("A contract with this name already exists in this system")).toBeInTheDocument();
  });
});
