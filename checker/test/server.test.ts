import { spawn } from "node:child_process";
import { createServer, type Server } from "node:http";
import { afterAll, beforeAll, describe, expect, test } from "vitest";
import type { CheckRequest, CheckResponse } from "../src/check.ts";
import { configFromEnv, handler, type CheckerConfig } from "../src/server.ts";

function listen(
  config: CheckerConfig,
  checkFn?: (request: CheckRequest) => Promise<CheckResponse>,
): Promise<{ server: Server; base: string }> {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      void handler(config, checkFn)(req, res);
    });
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      const port = typeof address === "object" && address ? address.port : 0;
      resolve({ server, base: `http://127.0.0.1:${port}` });
    });
  });
}

const OPENAPI = 'openapi: 3.1.0\ninfo:\n  title: T\n  version: "1"\npaths: {}\n';

describe("configFromEnv", () => {
  test("defaults and overrides", () => {
    expect(configFromEnv({})).toEqual({ port: 9090, token: undefined, maxBodyBytes: 2 * 1024 * 1024, timeoutMs: 20_000 });
    expect(configFromEnv({ PORT: "1", CHECKER_TOKEN: " t ", CHECKER_MAX_BYTES: "10", CHECKER_TIMEOUT_MS: "5" })).toEqual({
      port: 1,
      token: "t",
      maxBodyBytes: 10,
      timeoutMs: 5,
    });
    expect(configFromEnv({ CHECKER_TOKEN: "   " }).token).toBeUndefined();
  });
});

describe("the HTTP contract", () => {
  let server: Server;
  let base: string;
  beforeAll(async () => {
    ({ server, base } = await listen({ port: 0, token: "secret", maxBodyBytes: 4096, timeoutMs: 20_000 }));
  });
  afterAll(() => server.close());

  const post = (body: string, headers: Record<string, string> = { "x-checker-token": "secret" }) =>
    fetch(`${base}/check`, { method: "POST", headers: { "content-type": "application/json", ...headers }, body });

  test("GET /healthz reports ok and the engine roster without a token", async () => {
    const res = await fetch(`${base}/healthz`);
    expect(res.status).toBe(200);
    const body = (await res.json()) as { status: string; engines: { name: string }[] };
    expect(body.status).toBe("ok");
    expect(body.engines.map((e) => e.name)).toContain("@asyncapi/parser");
  });

  test("a valid request answers findings + engine", async () => {
    const res = await post(JSON.stringify({ type: "OPENAPI", content: OPENAPI }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { findings: unknown[]; engine: unknown[] };
    expect(Array.isArray(body.findings)).toBe(true);
    expect(body.engine.length).toBeGreaterThan(0);
  });

  test("a missing or wrong token is 401 problem+json", async () => {
    const missing = await post(JSON.stringify({ type: "ODCS", content: "a: 1" }), {});
    expect(missing.status).toBe(401);
    expect(missing.headers.get("content-type")).toBe("application/problem+json");
    const wrong = await post(JSON.stringify({ type: "ODCS", content: "a: 1" }), { "x-checker-token": "nope" });
    expect(wrong.status).toBe(401);
    expect(await wrong.json()).toMatchObject({ status: 401, title: "Unauthorized" });
  });

  test("malformed JSON and a wrong shape are 400", async () => {
    expect((await post("{ not json")).status).toBe(400);
    expect((await post(JSON.stringify({ type: "WSDL", content: "x" }))).status).toBe(400);
    expect((await post(JSON.stringify({ type: "OPENAPI" }))).status).toBe(400);
  });

  test("a body over the cap is 413", async () => {
    const res = await post(JSON.stringify({ type: "ODCS", content: "x".repeat(5000) }));
    expect(res.status).toBe(413);
  });

  test("unknown paths are 404 and a wrong method is 405", async () => {
    expect((await fetch(`${base}/nope`)).status).toBe(404);
    expect((await fetch(`${base}/check`)).status).toBe(405);
  });
});

describe("the per-request budget", () => {
  test("an exhausted budget is 504", async () => {
    const never = () => new Promise<CheckResponse>(() => undefined);
    const { server, base } = await listen({ port: 0, token: undefined, maxBodyBytes: 1 << 20, timeoutMs: 20 }, never);
    try {
      const res = await fetch(`${base}/check`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ type: "ODCS", content: "a: 1" }),
      });
      expect(res.status).toBe(504);
      expect(await res.json()).toMatchObject({ status: 504, title: "Gateway Timeout" });
    } finally {
      server.close();
    }
  });

  test("an engine crash is a 500 problem (the server treats it as CHECKER_UNAVAILABLE)", async () => {
    const boom = () => Promise.reject(new Error("engine exploded"));
    const { server, base } = await listen({ port: 0, token: undefined, maxBodyBytes: 1 << 20, timeoutMs: 1000 }, boom);
    try {
      const res = await fetch(`${base}/check`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ type: "ODCS", content: "a: 1" }),
      });
      expect(res.status).toBe(500);
    } finally {
      server.close();
    }
  });
});

describe("boot smoke (real Node ESM, not vitest's interop)", () => {
  // The Spectral/AsyncAPI packages are CJS: a named import that vitest resolves happily can
  // still blow up under `node` ("does not provide an export named …"). Boot the actual entry
  // point the image runs and hit /healthz, so that class of bug fails here, not in Docker.
  test("`tsx src/server.ts` starts and answers /healthz", async () => {
    // process.execPath (this very node binary) + the tsx loader — no PATH lookup.
    const child = spawn(process.execPath, ["--import", "tsx", "src/server.ts"], {
      cwd: new URL("..", import.meta.url),
      env: { ...process.env, PORT: "0", SCARF_ANALYTICS: "false" },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let output = "";
    try {
      const port = await new Promise<number>((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error(`checker did not boot:\n${output}`)), 25_000);
        const onData = (chunk: Buffer) => {
          output += chunk.toString();
          const match = /listening on :(\d+)/.exec(output);
          if (match) {
            clearTimeout(timer);
            resolve(Number(match[1]));
          }
        };
        child.stdout.on("data", onData);
        child.stderr.on("data", onData);
        child.on("exit", (code) => reject(new Error(`checker exited with ${code}:\n${output}`)));
      });
      const res = await fetch(`http://127.0.0.1:${port}/healthz`);
      expect(res.status).toBe(200);
      const checked = await fetch(`http://127.0.0.1:${port}/check`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ type: "OPENAPI", content: OPENAPI }),
      });
      expect(checked.status).toBe(200);
    } finally {
      child.kill();
    }
  }, 40_000);
});
