import type { TFunction } from "i18next";
import type { EnvironmentBody, EnvironmentResponse, KafkaSaslMechanism, KafkaSecurityProtocol } from "../api/environments";
import { ApiError } from "../api/http";
import { saveErrorMessage } from "./saveError";

// The server's limits (environments/Environment.kt) — keep in step.
export const MAX_ENVIRONMENT_NAME_LENGTH = 50;
export const MAX_ENVIRONMENT_DESCRIPTION_LENGTH = 2000;
export const KAFKA_SECURITY_PROTOCOLS = ["PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL"] as const satisfies readonly KafkaSecurityProtocol[];
export const KAFKA_SASL_MECHANISMS = ["PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512"] as const satisfies readonly KafkaSaslMechanism[];
const JDBC_PARAM_ALLOW_LIST = ["ssl", "sslmode", "currentSchema", "ApplicationName"];

const HOST_PORT = /^[A-Za-z0-9.-]+:\d{1,5}$/;

export type EnvironmentFormValues = {
  systemId: string | null;
  name: string;
  description: string;
  httpEnabled: boolean;
  httpBaseUrl: string;
  kafkaEnabled: boolean;
  bootstrapServers: string;
  securityProtocol: KafkaSecurityProtocol;
  saslMechanism: KafkaSaslMechanism | null;
  kafkaUsername: string;
  /** Blank on an edit = keep the stored password. */
  kafkaPassword: string;
  pgEnabled: boolean;
  jdbcUrl: string;
  pgUsername: string;
  pgPassword: string;
};

export const EMPTY_ENVIRONMENT_FORM: EnvironmentFormValues = {
  systemId: null,
  name: "",
  description: "",
  httpEnabled: true,
  httpBaseUrl: "",
  kafkaEnabled: false,
  bootstrapServers: "",
  securityProtocol: "PLAINTEXT",
  saslMechanism: null,
  kafkaUsername: "",
  kafkaPassword: "",
  pgEnabled: false,
  jdbcUrl: "",
  pgUsername: "",
  pgPassword: "",
};

export function isSasl(protocol: KafkaSecurityProtocol): boolean {
  return protocol === "SASL_PLAINTEXT" || protocol === "SASL_SSL";
}

/** The server's static http(s) base-URL rule, mirrored: absolute, no credentials, no query, no fragment. */
export function isHttpBaseUrl(raw: string): boolean {
  try {
    const url = new URL(raw);
    return (url.protocol === "http:" || url.protocol === "https:") && url.username === "" && url.password === "" && url.search === "" && url.hash === "";
  } catch {
    return false;
  }
}

export function isBootstrapServers(raw: string): boolean {
  const entries = raw.split(",").map((e) => e.trim()).filter(Boolean);
  return entries.length > 0 && entries.length <= 20 && entries.every((e) => HOST_PORT.test(e));
}

export function isJdbcUrl(raw: string): boolean {
  if (!raw.startsWith("jdbc:postgresql://")) return false;
  const query = raw.includes("?") ? raw.slice(raw.indexOf("?") + 1) : "";
  return query.split("&").filter(Boolean).every((pair) => JDBC_PARAM_ALLOW_LIST.includes(pair.split("=")[0]));
}

/**
 * Mantine `validate` rules mirroring the server. `existing` = the row being edited: a stored
 * password lets the password field stay blank (keep), a new target or a create needs one.
 */
export function environmentFormValidation(t: TFunction, existing: EnvironmentResponse | null) {
  const passwordNeeded = (has: boolean | undefined) => !(existing && has);
  return {
    systemId: (v: string | null) => (v ? null : t("environments.validation.systemRequired")),
    name: (v: string) => (v.trim().length >= 1 && v.trim().length <= MAX_ENVIRONMENT_NAME_LENGTH ? null : t("environments.validation.nameLength")),
    description: (v: string) => (v.length <= MAX_ENVIRONMENT_DESCRIPTION_LENGTH ? null : t("environments.validation.descriptionLength")),
    httpEnabled: (v: boolean, values: EnvironmentFormValues) => (v || values.kafkaEnabled || values.pgEnabled ? null : t("environments.validation.needsTarget")),
    httpBaseUrl: (v: string, values: EnvironmentFormValues) => (!values.httpEnabled || isHttpBaseUrl(v.trim()) ? null : t("environments.validation.httpBaseUrl")),
    bootstrapServers: (v: string, values: EnvironmentFormValues) => (!values.kafkaEnabled || isBootstrapServers(v) ? null : t("environments.validation.bootstrapServers")),
    saslMechanism: (v: KafkaSaslMechanism | null, values: EnvironmentFormValues) =>
      !values.kafkaEnabled || !isSasl(values.securityProtocol) || v ? null : t("environments.validation.saslMechanism"),
    kafkaUsername: (v: string, values: EnvironmentFormValues) =>
      !values.kafkaEnabled || !isSasl(values.securityProtocol) || v.trim() ? null : t("environments.validation.username"),
    kafkaPassword: (v: string, values: EnvironmentFormValues) =>
      !values.kafkaEnabled || !isSasl(values.securityProtocol) || v || !passwordNeeded(existing?.kafka?.hasPassword) ? null : t("environments.validation.password"),
    jdbcUrl: (v: string, values: EnvironmentFormValues) => (!values.pgEnabled || isJdbcUrl(v.trim()) ? null : t("environments.validation.jdbcUrl")),
    pgUsername: (v: string, values: EnvironmentFormValues) => (!values.pgEnabled || v.trim() ? null : t("environments.validation.username")),
    pgPassword: (v: string, values: EnvironmentFormValues) =>
      !values.pgEnabled || v || !passwordNeeded(existing?.postgres?.hasPassword) ? null : t("environments.validation.password"),
  };
}

/** The wire body. A blank password on an edit is OMITTED — the server keeps the stored one. */
export function toEnvironmentRequest(values: EnvironmentFormValues): EnvironmentBody {
  const sasl = isSasl(values.securityProtocol);
  return {
    systemId: Number(values.systemId),
    name: values.name.trim(),
    description: values.description.trim() || null,
    httpBaseUrl: values.httpEnabled ? values.httpBaseUrl.trim() : null,
    kafka: values.kafkaEnabled
      ? {
          bootstrapServers: values.bootstrapServers.replace(/\s/g, ""),
          securityProtocol: values.securityProtocol,
          saslMechanism: sasl ? values.saslMechanism : null,
          username: sasl ? values.kafkaUsername.trim() : null,
          ...(sasl && values.kafkaPassword ? { password: values.kafkaPassword } : {}),
        }
      : null,
    postgres: values.pgEnabled
      ? { jdbcUrl: values.jdbcUrl.trim(), username: values.pgUsername.trim(), ...(values.pgPassword ? { password: values.pgPassword } : {}) }
      : null,
  };
}

export function fromEnvironmentResponse(e: EnvironmentResponse): EnvironmentFormValues {
  return {
    systemId: String(e.systemId),
    name: e.name,
    description: e.description ?? "",
    httpEnabled: e.httpBaseUrl != null,
    httpBaseUrl: e.httpBaseUrl ?? "",
    kafkaEnabled: e.kafka != null,
    bootstrapServers: e.kafka?.bootstrapServers ?? "",
    securityProtocol: e.kafka?.securityProtocol ?? "PLAINTEXT",
    saslMechanism: e.kafka?.saslMechanism ?? null,
    kafkaUsername: e.kafka?.username ?? "",
    kafkaPassword: "",
    pgEnabled: e.postgres != null,
    jdbcUrl: e.postgres?.jdbcUrl ?? "",
    pgUsername: e.postgres?.username ?? "",
    pgPassword: "",
  };
}

export function environmentSaveErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError && err.status === 400 && err.detail) return err.detail;
  return saveErrorMessage(err, t, {
    forbidden: "environments.saveForbidden",
    notFound: "environments.saveGone",
    conflict: "environments.saveConflict",
    invalid: "environments.saveInvalid",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
