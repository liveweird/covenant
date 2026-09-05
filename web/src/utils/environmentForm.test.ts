import { describe, expect, test } from "vitest";
import type { TFunction } from "i18next";
import type { EnvironmentResponse } from "../api/environments";
import { EMPTY_ENVIRONMENT_FORM, environmentFormValidation, fromEnvironmentResponse, isBootstrapServers, isHttpBaseUrl, isJdbcUrl, toEnvironmentRequest } from "./environmentForm";

const t = ((key: string) => key) as unknown as TFunction;

const STORED: EnvironmentResponse = {
  id: 4, systemId: 7, systemName: "gateway", name: "staging", description: null, httpBaseUrl: "http://gw.internal:8080",
  kafka: { bootstrapServers: "b:9092", securityProtocol: "SASL_SSL", saslMechanism: "SCRAM-SHA-256", username: "svc", hasPassword: true },
  postgres: { jdbcUrl: "jdbc:postgresql://db:5432/app", username: "reader", hasPassword: true },
  createdAt: 1, updatedAt: 2,
};

describe("environmentForm", () => {
  test("the shape predicates mirror the server", () => {
    expect(isHttpBaseUrl("https://gw.internal/")).toBe(true);
    expect(isHttpBaseUrl("http://u:p@gw.internal/")).toBe(false);
    expect(isHttpBaseUrl("http://gw.internal/?x=1")).toBe(false);
    expect(isHttpBaseUrl("ftp://gw.internal/")).toBe(false);
    expect(isBootstrapServers("a:9092, b:9093")).toBe(true);
    expect(isBootstrapServers("a;9092")).toBe(false);
    expect(isJdbcUrl("jdbc:postgresql://db:5432/app?sslmode=require")).toBe(true);
    expect(isJdbcUrl("jdbc:postgresql://db:5432/app?sslfactory=x")).toBe(false);
    expect(isJdbcUrl("jdbc:mysql://db/app")).toBe(false);
  });

  test("validation needs a system, a name, at least one target, and passwords only where none is stored", () => {
    const create = environmentFormValidation(t, null);
    const values = { ...EMPTY_ENVIRONMENT_FORM, httpEnabled: false, kafkaEnabled: true, securityProtocol: "SASL_SSL" as const, bootstrapServers: "b:9092" };
    expect(create.systemId(null)).toBe("environments.validation.systemRequired");
    expect(create.name("")).toBe("environments.validation.nameLength");
    expect(create.httpEnabled(false, { ...EMPTY_ENVIRONMENT_FORM, httpEnabled: false })).toBe("environments.validation.needsTarget");
    expect(create.saslMechanism(null, values)).toBe("environments.validation.saslMechanism");
    expect(create.kafkaUsername("", values)).toBe("environments.validation.username");
    expect(create.kafkaPassword("", values)).toBe("environments.validation.password");
    const edit = environmentFormValidation(t, STORED);
    expect(edit.kafkaPassword("", values)).toBeNull();
    expect(edit.pgPassword("", { ...values, pgEnabled: true })).toBeNull();
    expect(environmentFormValidation(t, { ...STORED, postgres: null }).pgPassword("", { ...values, pgEnabled: true })).toBe("environments.validation.password");
  });

  test("the request omits a blank password (keep) and drops disabled targets; the response round-trips without secrets", () => {
    const values = fromEnvironmentResponse(STORED);
    expect(values.kafkaPassword).toBe("");
    const body = toEnvironmentRequest(values);
    expect(body.kafka).toEqual({ bootstrapServers: "b:9092", securityProtocol: "SASL_SSL", saslMechanism: "SCRAM-SHA-256", username: "svc" });
    expect(body.postgres).toEqual({ jdbcUrl: "jdbc:postgresql://db:5432/app", username: "reader" });
    const withPassword = toEnvironmentRequest({ ...values, pgPassword: "new-secret", kafkaEnabled: false });
    expect(withPassword.kafka).toBeNull();
    expect(withPassword.postgres).toEqual({ jdbcUrl: "jdbc:postgresql://db:5432/app", username: "reader", password: "new-secret" });
    expect(toEnvironmentRequest({ ...values, securityProtocol: "PLAINTEXT" }).kafka).toEqual({ bootstrapServers: "b:9092", securityProtocol: "PLAINTEXT", saslMechanism: null, username: null });
  });
});
