import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import Home from "./Home";

describe("Home", () => {
  test("renders the heading and the scaffold note", () => {
    renderWithProviders(<Home />);
    expect(screen.getByRole("heading", { level: 2, name: "Home" })).toBeInTheDocument();
    expect(screen.getByText(/contract repository/)).toBeInTheDocument();
  });
});
