import { fork } from "node:child_process";
import { fileURLToPath } from "node:url";
import type { CheckRequest, CheckResponse } from "./protocol.ts";

export class CheckTimeout extends Error {}
export class CheckBusy extends Error {}

type ChildReply = { response: CheckResponse } | { error: string };
type Outcome = { response: CheckResponse } | { error: Error };
type Job = {
  request: CheckRequest;
  timeoutMs: number;
  maxConcurrent: number;
  childUrl: URL;
  enqueuedAt: number;
  queueTimer?: NodeJS.Timeout;
  resolve: (response: CheckResponse) => void;
  reject: (error: Error) => void;
};

let activeChildren = 0;
const queue: Job[] = [];

/**
 * Parser and lint work runs in a child process. A V8 fatal error stays inside that process;
 * a private heap and SIGKILL at the deadline bound memory and CPU independently of the server.
 * A bounded FIFO absorbs ordinary parallel saves without exceeding the container memory budget.
 */
export function isolatedCheck(
  request: CheckRequest,
  timeoutMs: number,
  maxConcurrent: number,
  maxQueued: number,
  childUrl = defaultChildUrl(),
): Promise<CheckResponse> {
  return new Promise((resolve, reject) => {
    const job: Job = { request, timeoutMs, maxConcurrent, childUrl, enqueuedAt: Date.now(), resolve, reject };
    if (queue.length === 0 && activeChildren < maxConcurrent) {
      start(job);
      return;
    }
    if (queue.length >= maxQueued) {
      reject(new CheckBusy());
      return;
    }
    job.queueTimer = setTimeout(() => expireQueued(job), timeoutMs);
    queue.push(job);
  });
}

function start(job: Job): void {
  if (job.queueTimer) clearTimeout(job.queueTimer);
  const remainingMs = job.timeoutMs - (Date.now() - job.enqueuedAt);
  if (remainingMs <= 0) {
    job.reject(new CheckTimeout());
    drain();
    return;
  }
  activeChildren += 1;
  const release = (): void => {
    activeChildren -= 1;
    drain();
  };
  runChild(job.request, remainingMs, job.childUrl).then(
    (response) => {
      release();
      job.resolve(response);
    },
    (error: Error) => {
      release();
      job.reject(error);
    },
  );
}

function drain(): void {
  const next = queue[0];
  if (!next || activeChildren >= next.maxConcurrent) return;
  queue.shift();
  start(next);
}

function expireQueued(job: Job): void {
  const index = queue.indexOf(job);
  if (index < 0) return;
  queue.splice(index, 1);
  job.reject(new CheckTimeout());
  drain();
}

function runChild(request: CheckRequest, timeoutMs: number, childUrl: URL): Promise<CheckResponse> {
  return new Promise((resolve, reject) => {
    const sourceChild = childUrl.pathname.endsWith(".ts");
    const child = fork(fileURLToPath(childUrl), [], {
      execArgv: ["--max-old-space-size=96", "--stack-size=4096", ...(sourceChild ? ["--import", "tsx"] : [])],
      env: { NODE_ENV: process.env.NODE_ENV, SCARF_ANALYTICS: "false" },
      stdio: ["ignore", "ignore", "ignore", "ipc"],
    });
    let settled = false;
    const finish = (outcome: Outcome): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      const settleOutcome = (): void => {
        if ("response" in outcome) resolve(outcome.response);
        else reject(outcome.error);
      };
      if (child.exitCode !== null || child.signalCode !== null || child.pid === undefined) settleOutcome();
      else {
        child.once("exit", settleOutcome);
        if (!child.kill("SIGKILL")) settleOutcome();
      }
    };
    const timer = setTimeout(() => finish({ error: new CheckTimeout() }), timeoutMs);
    child.once("message", (message: ChildReply) => {
      if ("response" in message) finish({ response: message.response });
      else finish({ error: new Error(message.error) });
    });
    child.once("error", (error) => finish({ error }));
    child.once("exit", (code, signal) => {
      if (!settled) finish({ error: new Error(`Check child exited with code ${code ?? "null"} (${signal ?? "no signal"})`) });
    });
    child.send(request, (error) => {
      if (error) finish({ error });
    });
  });
}

function defaultChildUrl(): URL {
  return new URL(import.meta.url.endsWith(".ts") ? "./check-child.ts" : "./check-child.js", import.meta.url);
}
