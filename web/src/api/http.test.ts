import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { consumeSignedOut } from "../auth";
import { jsonResponse } from "../test/http";
import { authedFetch } from "./http";
import type { components } from "./schema";
import { getRefreshToken, getToken, persistSession } from "./session";

const session = (token: string, refreshToken: string): components["schemas"]["LoginResponse"] => ({
  token,
  expiresAt: 1,
  refreshToken,
  refreshExpiresAt: 2,
  userId: 1,
  roles: [],
  disabledFeatures: [],
  language: "en",
});

const bearer = (init?: RequestInit) => new Headers(init?.headers).get("Authorization");
const isRefresh = (url: unknown) => String(url).endsWith("/api/v1/refresh");

/** The session-continuity core: one silent refresh, one retry, and only a DEFINITIVE rejection ends the session. */
describe("authedFetch", () => {
  const fetchMock = vi.fn<(url: string, init?: RequestInit) => Promise<Response>>();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    persistSession(session("t1", "r1"));
    consumeSignedOut();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("a 401 triggers ONE refresh shared by concurrent callers, and each request retries with the new token", async () => {
    fetchMock.mockImplementation((url, init) => {
      if (isRefresh(url)) return Promise.resolve(jsonResponse(200, session("t2", "r2")));
      return Promise.resolve(bearer(init) === "Bearer t2" ? jsonResponse(200, { ok: true }) : jsonResponse(401, {}));
    });
    const [users, teams] = await Promise.all([authedFetch("/api/v1/users"), authedFetch("/api/v1/teams")]);
    expect(users.status).toBe(200);
    expect(teams.status).toBe(200);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1);
    expect(getToken()).toBe("t2");
    expect(getRefreshToken()).toBe("r2");
  });

  test("a rejected refresh ends the session and hands the original 401 back", async () => {
    fetchMock.mockImplementation(() => Promise.resolve(jsonResponse(401, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(getToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(consumeSignedOut()).toBe(true);
  });

  test("an unreachable refresh keeps the session — the 401 is the caller's error, nobody is signed out", async () => {
    fetchMock.mockImplementation((url) =>
      isRefresh(url) ? Promise.reject(new TypeError("network down")) : Promise.resolve(jsonResponse(401, {})),
    );
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(getToken()).toBe("t1");
    expect(getRefreshToken()).toBe("r1");
    expect(consumeSignedOut()).toBe(false);
  });

  test("a 5xx or a malformed refresh answer is transient too", async () => {
    fetchMock.mockImplementation((url) => Promise.resolve(isRefresh(url) ? jsonResponse(503, {}) : jsonResponse(401, {})));
    expect((await authedFetch("/api/v1/users")).status).toBe(401);
    fetchMock.mockImplementation((url) =>
      Promise.resolve(isRefresh(url) ? jsonResponse(200, { unexpected: true }) : jsonResponse(401, {})),
    );
    expect((await authedFetch("/api/v1/users")).status).toBe(401);
    expect(getToken()).toBe("t1");
    expect(consumeSignedOut()).toBe(false);
  });

  test("after a successful refresh the request is retried once, never twice", async () => {
    fetchMock.mockImplementation((url) => Promise.resolve(isRefresh(url) ? jsonResponse(200, session("t2", "r2")) : jsonResponse(401, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/api/v1/users"))).toHaveLength(2);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(1);
    expect(getToken()).toBe("t2");
  });

  test("a request without a stored token goes out unauthenticated and a 401 needs no refresh call", async () => {
    localStorage.clear();
    fetchMock.mockImplementation((_url, init) => Promise.resolve(bearer(init) === null ? jsonResponse(401, {}) : jsonResponse(500, {})));
    const res = await authedFetch("/api/v1/users");
    expect(res.status).toBe(401);
    expect(fetchMock.mock.calls.filter(([url]) => isRefresh(url))).toHaveLength(0);
  });
});
