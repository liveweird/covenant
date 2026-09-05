import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { hasTryTarget, statusColor, templateParams, tryErrorMessage, tryTargetOf } from "./tryIt";

const env = { id: 1, systemId: 1, systemName: "s", name: "e", description: null, httpBaseUrl: null, kafka: null, postgres: null, createdAt: 1, updatedAt: 1 };

describe("tryIt utils", () => {
  test("the leg per contract type and the environment target it needs", () => {
    expect(tryTargetOf("OPENAPI")).toBe("http");
    expect(tryTargetOf("ASYNCAPI")).toBe("kafka");
    expect(tryTargetOf("ODCS")).toBe("postgres");
    expect(hasTryTarget({ ...env, httpBaseUrl: "http://x" }, "http")).toBe(true);
    expect(hasTryTarget(env, "http")).toBe(false);
    expect(hasTryTarget({ ...env, kafka: { bootstrapServers: "k:1", securityProtocol: "PLAINTEXT", saslMechanism: null, username: null, hasPassword: false } }, "kafka")).toBe(true);
    expect(hasTryTarget({ ...env, postgres: { jdbcUrl: "jdbc:postgresql://d/x", username: "u", hasPassword: true } }, "postgres")).toBe(true);
    expect(hasTryTarget(env, "postgres")).toBe(false);
  });

  test("template parameters and status colours", () => {
    expect(templateParams("/pets/{id}/toys/{toyId}")).toEqual(["id", "toyId"]);
    expect(templateParams("/pets")).toEqual([]);
    expect([200, 302, 404, 503].map(statusColor)).toEqual(["teal", "gray", "orange", "red"]);
  });

  test("the server's 400/502 details are shown verbatim, other statuses map to the fixed vocabulary", () => {
    const t = i18n.t;
    const problem = (status: number, detail?: string) => new ApiError(status, { title: "x", status, detail });
    expect(tryErrorMessage(problem(400, "Header 'Host' is set by the server"), t)).toBe("Header 'Host' is set by the server");
    expect(tryErrorMessage(problem(502, "The environment could not be reached"), t)).toBe("The environment could not be reached");
    expect(tryErrorMessage(problem(400), t)).toBe("The request was refused.");
    expect(tryErrorMessage(problem(403), t)).toBe("Only the contract's writers may do this.");
    expect(tryErrorMessage(problem(429), t)).toBe("Too many tries — wait a minute.");
    expect(tryErrorMessage(problem(413), t)).toBe("The try failed (413)");
    expect(tryErrorMessage(new TypeError("Failed to fetch"), t)).toBe("The try failed. Check your connection and try again.");
  });
});
