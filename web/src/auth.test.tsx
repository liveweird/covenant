import { describe, expect, test } from "vitest";
import { Route, Routes } from "react-router-dom";
import { renderWithProviders, screen } from "./test/render";
import { RedirectIfAuthed, RequireAdmin, RequireAuth, consumeSignedOut, flagSignedOut } from "./auth";

const TOKEN_KEY = "covenant.auth.token";

function TestRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      <Route element={<RequireAuth />}>
        <Route path="/" element={<div>home page</div>} />
        <Route path="/secret" element={<div>secret page</div>} />
        <Route element={<RequireAdmin />}>
          <Route path="/admin-only" element={<div>admin page</div>} />
        </Route>
      </Route>
    </Routes>
  );
}

describe("route guards", () => {
  test("RequireAuth redirects an anonymous visitor to /login", () => {
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("login page")).toBeInTheDocument();
  });

  test("RequireAuth renders the outlet for an authenticated visitor", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(<TestRoutes />, { route: "/secret" });
    expect(screen.getByText("secret page")).toBeInTheDocument();
  });

  test("RedirectIfAuthed bounces an authenticated visitor off /login", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    renderWithProviders(
      <Routes>
        <Route path="/" element={<div>home page</div>} />
        <Route path="/login" element={<RedirectIfAuthed><div>login page</div></RedirectIfAuthed>} />
      </Routes>,
      { route: "/login" },
    );
    expect(screen.getByText("home page")).toBeInTheDocument();
  });

  test("RequireAdmin sends a regular user home and renders the outlet for an admin", () => {
    localStorage.setItem(TOKEN_KEY, "token");
    localStorage.setItem("covenant.auth.roles", "[]");
    renderWithProviders(<TestRoutes />, { route: "/admin-only" });
    expect(screen.getByText("home page")).toBeInTheDocument();
    localStorage.setItem("covenant.auth.roles", JSON.stringify(["ADMIN"]));
    renderWithProviders(<TestRoutes />, { route: "/admin-only" });
    expect(screen.getByText("admin page")).toBeInTheDocument();
  });

  test("the signed-out flag is one-shot", () => {
    flagSignedOut();
    expect(consumeSignedOut()).toBe(true);
    expect(consumeSignedOut()).toBe(false);
  });
});
