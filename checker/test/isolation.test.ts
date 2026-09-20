import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { describe, expect, test } from "vitest";
import { CheckBusy, CheckTimeout, isolatedCheck } from "../src/isolation.ts";
import type { CheckRequest } from "../src/protocol.ts";

const childUrl = new URL("./fixtures/isolation-child.mjs", import.meta.url);
const request = (content: string): CheckRequest => ({ type: "ODCS", content });

describe("isolatedCheck", () => {
  test("releases capacity before resolving a completed production child", async () => {
    const cyclic = request("asyncapi: 3.0.0\ninfo: {title: t, version: 1.0.0}\nchannels: {}\nx-cycle: &a\n  self: *a\n");
    cyclic.type = "ASYNCAPI";
    await expect(isolatedCheck(cyclic, 5_000, 1, 0)).resolves.toMatchObject({
      findings: [expect.objectContaining({ code: "cyclic-alias-not-allowed" })],
    });
    await expect(isolatedCheck(request("a: 1"), 5_000, 1, 0)).resolves.toMatchObject({ findings: [] });
  });

  test("kills CPU-bound work at the deadline and admits a healthy following request", async () => {
    const directory = mkdtempSync(join(tmpdir(), "covenant-checker-"));
    const marker = join(directory, "cpu-started");
    try {
      const running = isolatedCheck(request(`cpu:${marker}`), 1_000, 1, 0, childUrl);
      const runningResult = expect(running).rejects.toBeInstanceOf(CheckTimeout);
      await waitFor(() => existsSync(marker));
      const pid = Number(readFileSync(marker, "utf8"));
      await runningResult;
      expect(() => process.kill(pid, 0)).toThrow(expect.objectContaining({ code: "ESRCH" }));
      await expect(isolatedCheck(request("healthy"), 5_000, 1, 0, childUrl)).resolves.toEqual({ findings: [], engine: [] });
    } finally {
      rmSync(directory, { recursive: true, force: true });
    }
  });

  test("contains an aborting child and admits a healthy following request", async () => {
    await expect(isolatedCheck(request("crash"), 5_000, 1, 0, childUrl)).rejects.toThrow(/Check child exited/);
    await expect(isolatedCheck(request("healthy"), 5_000, 1, 0, childUrl)).resolves.toEqual({ findings: [], engine: [] });
  });

  test("queues bounded parallel work and rejects only beyond the queue cap", async () => {
    const running = isolatedCheck(request("cpu"), 100, 1, 1, childUrl);
    const runningResult = expect(running).rejects.toBeInstanceOf(CheckTimeout);
    const queued = isolatedCheck(request("healthy"), 5_000, 1, 1, childUrl);
    await expect(isolatedCheck(request("healthy"), 5_000, 1, 1, childUrl)).rejects.toBeInstanceOf(CheckBusy);
    await runningResult;
    await expect(queued).resolves.toEqual({ findings: [], engine: [] });
  });

  test("queued time counts against the request deadline", async () => {
    const running = isolatedCheck(request("cpu"), 200, 1, 1, childUrl);
    const runningResult = expect(running).rejects.toBeInstanceOf(CheckTimeout);
    await expect(isolatedCheck(request("healthy"), 50, 1, 1, childUrl)).rejects.toBeInstanceOf(CheckTimeout);
    await runningResult;
  });
});

async function waitFor(predicate: () => boolean): Promise<void> {
  const deadline = Date.now() + 5_000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("Timed out waiting for child marker");
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
