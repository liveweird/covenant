import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { timingSafeEqual } from "node:crypto";
import { ENGINES } from "./engines/index.ts";
import { CheckBusy, CheckTimeout, isolatedCheck } from "./isolation.ts";
import { isCheckRequest, type CheckRequest, type CheckResponse } from "./protocol.ts";

/** Runtime knobs — every default documented in .claude/docs/checker.md. */
export interface CheckerConfig {
  port: number;
  token: string | undefined;
  maxBodyBytes: number;
  timeoutMs: number;
  maxConcurrentChecks: number;
  maxQueuedChecks: number;
}

const MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;
const DOCUMENTS_PER_REQUEST = 2;
const MAX_JSON_ESCAPE_EXPANSION = 2;
const JSON_ENVELOPE_BYTES = 1024;
const DEFAULT_MAX_BODY_BYTES =
  MAX_DOCUMENT_BYTES * DOCUMENTS_PER_REQUEST * MAX_JSON_ESCAPE_EXPANSION + JSON_ENVELOPE_BYTES;

export function configFromEnv(env: NodeJS.ProcessEnv = process.env): CheckerConfig {
  return {
    port: Number(env.PORT ?? 9090),
    token: env.CHECKER_TOKEN?.trim() || undefined,
    // The JVM can send a 2 MiB candidate plus a 2 MiB AsyncAPI baseline. JSON quoting can
    // double every byte (quotes, backslashes and accepted whitespace), and the envelope needs
    // a small fixed margin. Keep this a whole-request DoS bound, not a competing document cap.
    maxBodyBytes: Number(env.CHECKER_MAX_BYTES ?? DEFAULT_MAX_BODY_BYTES),
    timeoutMs: Number(env.CHECKER_TIMEOUT_MS ?? 20_000),
    maxConcurrentChecks: Number(env.CHECKER_MAX_CONCURRENT ?? 1),
    maxQueuedChecks: Number(env.CHECKER_MAX_QUEUED ?? 8),
  };
}

const PROBLEM_JSON = "application/problem+json";

function respondJson(res: ServerResponse, status: number, body: unknown, contentType = "application/json"): void {
  const text = JSON.stringify(body);
  res.writeHead(status, { "content-type": contentType, "content-length": Buffer.byteLength(text) });
  res.end(text);
}

/** RFC 7807, the main API's shape (API-ERR-001/002). */
function respondProblem(res: ServerResponse, status: number, title: string, detail: string, instance: string): void {
  respondJson(res, status, { type: "about:blank", title, status, detail, instance }, PROBLEM_JSON);
}

class BodyTooLarge extends Error {}

function readBody(req: IncomingMessage, maxBytes: number): Promise<string> {
  return new Promise((resolve, reject) => {
    const declared = Number(req.headers["content-length"] ?? 0);
    if (declared > maxBytes) {
      reject(new BodyTooLarge());
      return;
    }
    const chunks: Buffer[] = [];
    let received = 0;
    req.on("data", (chunk: Buffer) => {
      received += chunk.length;
      if (received > maxBytes) {
        reject(new BodyTooLarge());
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

function tokenMatches(config: CheckerConfig, req: IncomingMessage): boolean {
  if (!config.token) return true;
  const header = req.headers["x-checker-token"];
  const presented = Array.isArray(header) ? header[0] : header;
  if (!presented) return false;
  const a = Buffer.from(presented);
  const b = Buffer.from(config.token);
  return a.length === b.length && timingSafeEqual(a, b);
}

function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new CheckTimeout()), ms);
    promise.then(
      (value) => {
        clearTimeout(timer);
        resolve(value);
      },
      (error: unknown) => {
        clearTimeout(timer);
        reject(error instanceof Error ? error : new Error(String(error)));
      },
    );
  });
}

/** `checkFn` is a test seam (a never-resolving check exercises the 504 path); production passes nothing. */
export function handler(config: CheckerConfig, checkFn?: (request: CheckRequest) => Promise<CheckResponse>) {
  return async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    const url = new URL(req.url ?? "/", "http://checker");
    const instance = url.pathname;
    if (req.method === "GET" && url.pathname === "/healthz") {
      respondJson(res, 200, { status: "ok", engines: ENGINES });
      return;
    }
    if (url.pathname !== "/check") {
      respondProblem(res, 404, "Not Found", "Unknown path", instance);
      return;
    }
    if (req.method !== "POST") {
      respondProblem(res, 405, "Method Not Allowed", "Use POST /check", instance);
      return;
    }
    if (!tokenMatches(config, req)) {
      respondProblem(res, 401, "Unauthorized", "Missing or wrong X-Checker-Token", instance);
      return;
    }
    if (!isJsonContentType(req.headers["content-type"])) {
      respondProblem(res, 415, "Unsupported Media Type", "Content-Type must be application/json", instance);
      return;
    }
    let body: string;
    try {
      body = await readBody(req, config.maxBodyBytes);
    } catch (error) {
      if (error instanceof BodyTooLarge) {
        respondProblem(res, 413, "Payload Too Large", `Request body exceeds ${config.maxBodyBytes} bytes`, instance);
      } else {
        respondProblem(res, 400, "Bad Request", "Request body could not be read", instance);
      }
      return;
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(body);
    } catch {
      respondProblem(res, 400, "Bad Request", "Request body is not JSON", instance);
      return;
    }
    if (!isCheckRequest(parsed)) {
      respondProblem(res, 400, "Bad Request", "Expected only {type: OPENAPI|ASYNCAPI|ODCS, content: string, previousContent?: string}", instance);
      return;
    }
    try {
      const result = checkFn
        ? await withTimeout(checkFn(parsed), config.timeoutMs)
        : await isolatedCheck(parsed, config.timeoutMs, config.maxConcurrentChecks, config.maxQueuedChecks);
      respondJson(res, 200, result);
    } catch (error) {
      if (error instanceof CheckTimeout) {
        respondProblem(res, 504, "Gateway Timeout", `Check exceeded ${config.timeoutMs} ms`, instance);
      } else if (error instanceof CheckBusy) {
        respondProblem(res, 503, "Service Unavailable", "Checker queue is full", instance);
      } else {
        // An engine crash on a hostile document is the checker's bug, not the caller's — the
        // JVM treats any non-2xx as CHECKER_UNAVAILABLE and proceeds on its own verdicts.
        console.error("[checker] check failed", error);
        respondProblem(res, 500, "Internal Server Error", "Check failed", instance);
      }
    }
  };
}

function isJsonContentType(header: string | string[] | undefined): boolean {
  const value = Array.isArray(header) ? header[0] : header;
  return value?.split(";", 1)[0]?.trim().toLowerCase() === "application/json";
}

export function startServer(config: CheckerConfig = configFromEnv()) {
  const server = createServer((req, res) => {
    void handler(config)(req, res);
  });
  server.listen(config.port, "0.0.0.0", () => {
    const address = server.address();
    const port = typeof address === "object" && address ? address.port : config.port;
    const roster = ENGINES.map((e) => e.name + "@" + e.version).join(", ");
    // The port line is a contract: the boot smoke test (and PORT=0 users) read it.
    console.log(`[checker] listening on :${port} (${roster})`);
  });
  return server;
}

// `node dist/server.js` / `tsx src/server.ts` starts listening; the tests import the handler.
if (process.argv[1] && /server\.(ts|js)$/.test(process.argv[1])) {
  startServer();
}
