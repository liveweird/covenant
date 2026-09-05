// Shared fixtures + a route-table fetch mock for the contracts feature's page tests.
import type { Mock } from "vitest";
import { jsonResponse } from "./http";

const TOKEN_KEY = "covenant.auth.token";
const ROLES_KEY = "covenant.auth.roles";
const USER_ID_KEY = "covenant.auth.userId";

export function signIn(roles: string[] = ["ADMIN"], userId = 1) {
  localStorage.setItem(TOKEN_KEY, "fake-token");
  localStorage.setItem(ROLES_KEY, JSON.stringify(roles));
  localStorage.setItem(USER_ID_KEY, String(userId));
}

export const OWNER_TEAM = { kind: "TEAM" as const, id: 3, name: "Payments Team", deleted: false };
const LATEST = { id: 11, version: "1.1.0", lifecycle: "DRAFT" as const, checkErrors: 0, checkWarnings: 1, checkComplete: true };
export const CONTRACT = {
  id: 5,
  system: { id: 7, name: "gateway" },
  domain: { id: 1, name: "Payments" },
  type: "OPENAPI" as const,
  name: "orders-api",
  description: "Orders",
  owner: OWNER_TEAM,
  latestVersion: LATEST,
  versionCount: 2,
  canWrite: true,
  createdBy: 1,
  createdAt: 1,
  updatedAt: 2,
};
export const CONTRACT_PAGE = { items: [CONTRACT], page: 1, pageSize: 20, total: 1 };
export const CONTENT = "openapi: 3.1.0\ninfo:\n  title: Orders\n  version: 1.1.0\npaths: {}\n";
const OLD_CONTENT = "openapi: 3.1.0\ninfo:\n  title: Orders\n  version: 1.0.0\npaths: {}\n";
const VERSION_ITEM = {
  id: 11, contractId: 5, version: "1.1.0", lifecycle: "DRAFT" as const, format: "yaml" as const, docTitle: "Orders", specVersion: "3.1.0",
  checkErrors: 0, checkWarnings: 1, checkInfos: 0, checkComplete: true, createdBy: 1, createdAt: 1, updatedAt: 2,
};
const OLD_VERSION_ITEM = { ...VERSION_ITEM, id: 10, version: "1.0.0", lifecycle: "ACTIVE" as const, checkWarnings: 0 };
export const VERSION_PAGE = { items: [VERSION_ITEM, OLD_VERSION_ITEM], page: 1, pageSize: 20, total: 2 };
export const FINDING = { severity: "WARN" as const, source: "LINT" as const, code: "info-contact", message: "Info object must have contact", path: "/info", line: 2, column: 1 };
export const SOFT_ERROR = { severity: "ERROR" as const, source: "SCHEMA" as const, code: "OAS_PARSE", message: "paths is required", path: "/", line: 1, column: 1 };
export const VERSION = { ...VERSION_ITEM, content: CONTENT, contentSha256: "a".repeat(64), docDescription: null, findings: [FINDING], checkedAt: 2 };
export const OLD_VERSION = { ...OLD_VERSION_ITEM, content: OLD_CONTENT, contentSha256: "b".repeat(64), docDescription: null, findings: [], checkedAt: 2 };
export const CLEAN_REPORT = { format: "yaml", specVersion: "3.1.0", title: "Orders", description: null, declaredVersion: "1.1.0", findings: [], errors: 0, warnings: 0, infos: 0, checkerAvailable: true };
export const TREE = {
  domains: [
    { id: 1, name: "Payments", systems: [{ id: 7, name: "gateway", contracts: [{ id: 5, name: "orders-api", type: "OPENAPI", owner: OWNER_TEAM, latestVersion: LATEST, versionCount: 2, canWrite: true }] }] },
    { id: 2, name: "Identity", systems: [] },
  ],
};
export const SYSTEMS_PAGE = { items: [{ id: 7, domainId: 1, domainName: "Payments", name: "gateway", description: null, contractCount: 1, createdAt: 1, updatedAt: 2 }], page: 1, pageSize: 100, total: 1 };
const DOMAINS_PAGE = { items: [{ id: 1, name: "Payments", description: null, systemCount: 1, createdAt: 1, updatedAt: 2 }], page: 1, pageSize: 100, total: 1 };
const TEAMS_PAGE = { items: [{ id: 3, name: "Payments Team", description: null, memberCount: 1, createdAt: 1, updatedAt: 2 }], page: 1, pageSize: 100, total: 1 };
const USERS_PAGE = { items: [{ id: 1, name: "Admin User", email: "admin@covenant.local", roles: ["ADMIN"], disabledFeatures: [], language: "en" }], page: 1, pageSize: 50, total: 1 };
const ME = { id: 2, name: "Reg User", email: "reg@covenant.local", roles: [], disabledFeatures: [], language: "en" };

export type Route = { status: number; body?: unknown } | ((url: string, init?: RequestInit) => { status: number; body?: unknown });
export type FetchMock = Mock;

/**
 * A route table: exact `"METHOD /path"` keys first, then prefix keys ending in `?` matched by
 * `startsWith` (the paged lists), else a 404 problem. The registry/picker endpoints every page
 * touches are pre-wired so a test names only what it is about.
 */
export function serve(mockFetch: FetchMock, routes: Record<string, Route> = {}) {
  const table: Record<string, Route> = {
    "GET /api/v1/systems?": { status: 200, body: SYSTEMS_PAGE },
    "GET /api/v1/domains?": { status: 200, body: DOMAINS_PAGE },
    "GET /api/v1/teams?": { status: 200, body: TEAMS_PAGE },
    "GET /api/v1/users?": { status: 200, body: USERS_PAGE },
    "GET /api/v1/users/2": { status: 200, body: ME },
    ...routes,
  };
  mockFetch.mockImplementation((url: string, init?: RequestInit) => {
    const method = init?.method ?? "GET";
    const key = `${method} ${url}`;
    let route = table[key];
    if (!route) {
      const prefix = Object.keys(table).find((k) => k.endsWith("?") && key.startsWith(k));
      if (prefix) route = table[prefix];
    }
    if (!route) return Promise.resolve(jsonResponse(404, { title: "Not Found", status: 404 }));
    const r = typeof route === "function" ? route(url, init) : route;
    return Promise.resolve(r.body === undefined ? new Response(null, { status: r.status }) : jsonResponse(r.status, r.body));
  });
}

export function findCall(mockFetch: FetchMock, method: string, url: string) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && u === url);
}

export function calledUrl(mockFetch: FetchMock, method: string, predicate: (url: string) => boolean) {
  return mockFetch.mock.calls.find(([u, init]) => ((init as RequestInit | undefined)?.method ?? "GET") === method && typeof u === "string" && predicate(u));
}

export function bodyOf(call: unknown[] | undefined): unknown {
  return JSON.parse(((call?.[1] as RequestInit | undefined)?.body as string) ?? "null");
}
