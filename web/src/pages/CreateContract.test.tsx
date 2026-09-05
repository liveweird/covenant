import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { fireEvent, renderWithProviders, screen, waitFor, within } from "../test/render";
import { Route, Routes } from "react-router-dom";
import CreateContract from "./CreateContract";
import { bodyOf, CONTRACT, findCall, serve, signIn, type FetchMock } from "../test/contractsFixtures";

function renderPage(route = "/contracts/new") {
  return renderWithProviders(
    <Routes>
      <Route path="/contracts/new" element={<CreateContract />} />
      <Route path="/contracts/:id" element={<h2>Contract page</h2>} />
    </Routes>,
    { route },
  );
}

describe("CreateContract page", () => {
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

  test("validation names every required field", async () => {
    serve(mockFetch);
    renderPage();
    await screen.findByRole("heading", { level: 2, name: "New contract" });
    await userEvent.setup().click(screen.getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("Pick a system")).toBeInTheDocument();
    expect(screen.getByText("Name must be 1–100 characters")).toBeInTheDocument();
    expect(screen.getByText("Pick an owner")).toBeInTheDocument();
  });

  test("posts the record and lands on the contract's page; ?systemId= preselects the system", async () => {
    serve(mockFetch, { "POST /api/v1/contracts": { status: 201, body: CONTRACT } });
    const user = userEvent.setup();
    renderPage("/contracts/new?systemId=7");
    await waitFor(() => expect(screen.getByLabelText("System", { selector: "input" })).toHaveValue("gateway"));
    fireEvent.click(screen.getByLabelText("Type", { selector: "input" }));
    fireEvent.click(await screen.findByRole("option", { name: "ODCS" }));
    await user.type(screen.getByLabelText("Name"), "order-views");
    await user.type(screen.getByLabelText("Description"), "Read models");
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Payments Team" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));
    await waitFor(() => expect(findCall(mockFetch, "POST", "/api/v1/contracts")).toBeDefined());
    expect(bodyOf(findCall(mockFetch, "POST", "/api/v1/contracts"))).toEqual({
      systemId: 7, type: "ODCS", name: "order-views", description: "Read models", ownerTeamId: 3, ownerUserId: null,
    });
    expect(await screen.findByRole("heading", { level: 2, name: "Contract page" })).toBeInTheDocument();
  });

  test("a 409 marks the name; a 403 renders the forbidden vocabulary", async () => {
    serve(mockFetch, { "POST /api/v1/contracts": { status: 409, body: { title: "Conflict", status: 409 } } });
    const user = userEvent.setup();
    const { unmount } = renderPage("/contracts/new?systemId=7");
    await waitFor(() => expect(screen.getByLabelText("System", { selector: "input" })).toHaveValue("gateway"));
    await user.type(screen.getByLabelText("Name"), "orders-api");
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Payments Team" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText("A contract with this name already exists in this system")).toBeInTheDocument();
    unmount();
    serve(mockFetch, { "POST /api/v1/contracts": { status: 403, body: { title: "Forbidden", status: 403 } } });
    renderPage("/contracts/new?systemId=7");
    await waitFor(() => expect(screen.getByLabelText("System", { selector: "input" })).toHaveValue("gateway"));
    await user.type(screen.getByLabelText("Name"), "orders-api");
    await user.click(screen.getByLabelText("Owner", { selector: "input" }));
    await user.click(await screen.findByRole("option", { name: "Payments Team" }));
    await user.click(screen.getByRole("button", { name: /^create$/i }));
    expect(await screen.findByText(/You cannot edit this contract/)).toBeInTheDocument();
    expect(within(screen.getByRole("alert")).getByText(/only its owners/)).toBeInTheDocument();
  });
});
