import type { TFunction } from "i18next";
import type { ToadieConnection, ToadieConnectionBody } from "../api/toadie";
import { ApiError } from "../api/http";
import { saveErrorMessage } from "./saveError";
import { nameRule } from "./formRules";

export const MAX_TOADIE_NAME_LENGTH = 100;
const DEFAULT_TOADIE_MAPPING = {
  serviceBlueprint: "service",
  apiBlueprint: "api",
  providesRelation: "provides_apis",
  consumesRelation: "consumes_apis",
  systemRelation: "system",
} as const;

const DEFAULT_REGISTRY_MAPPING = {
  domainBlueprint: "domain",
  systemDomainRelation: "domain",
  domainParentRelation: "parent_domain",
  flattenDomains: false,
  domainDescriptionProperty: "",
  systemDescriptionProperty: "",
  teamDescriptionProperty: "",
} as const;

type RegistryMappingValues = {
  domainBlueprint: string;
  systemDomainRelation: string;
  domainParentRelation: string;
  flattenDomains: boolean;
  domainDescriptionProperty: string;
  systemDescriptionProperty: string;
  teamDescriptionProperty: string;
};

export type ToadieFormValues = {
  name: string;
  baseUrl: string;
  browserUrl: string;
  apiKey: string;
  enabled: boolean;
  refreshIntervalMinutes: number | string;
  serviceBlueprint: string;
  apiBlueprint: string;
  providesRelation: string;
  consumesRelation: string;
  systemRelation: string;
  registryEnabled: boolean;
  registryMapping: RegistryMappingValues;
};

export const EMPTY_TOADIE_FORM: ToadieFormValues = {
  name: "",
  baseUrl: "",
  browserUrl: "",
  apiKey: "",
  enabled: true,
  refreshIntervalMinutes: 60,
  ...DEFAULT_TOADIE_MAPPING,
  registryEnabled: false,
  registryMapping: DEFAULT_REGISTRY_MAPPING,
};

function isHttpUrl(raw: string): boolean {
  try {
    const url = new URL(raw);
    return (url.protocol === "http:" || url.protocol === "https:") && Boolean(url.hostname) && !url.username && !url.password && !url.search && !url.hash;
  } catch {
    return false;
  }
}

// Port/Toadie blueprint and relation identifiers may be qualified (for example
// `catalog/api:v1=public`); mirror the server's allow-list rather than URL-encoding them.
const IDENTIFIER = /^[A-Za-z0-9@_.:/=-]{1,100}$/;

export function toadieFormValidation(t: TFunction, existing: ToadieConnection | null) {
  const mappingRule = (value: string) => (IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"));
  return {
    name: nameRule(t, "toadie.validation.name", MAX_TOADIE_NAME_LENGTH),
    baseUrl: (value: string) => (isHttpUrl(value.trim()) ? null : t("toadie.validation.url")),
    browserUrl: (value: string) => (isHttpUrl(value.trim()) ? null : t("toadie.validation.url")),
    apiKey: (value: string) => (existing?.hasApiKey || value.trim() ? null : t("toadie.validation.apiKey")),
    refreshIntervalMinutes: (value: number | string) => {
      const number = Number(value);
      return Number.isInteger(number) && number >= 1 && number <= 10_080 ? null : t("toadie.validation.refreshInterval");
    },
    serviceBlueprint: mappingRule,
    apiBlueprint: mappingRule,
    providesRelation: mappingRule,
    consumesRelation: mappingRule,
    systemRelation: mappingRule,
    registryMapping: {
      domainBlueprint: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      systemDomainRelation: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      domainParentRelation: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      domainDescriptionProperty: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      systemDescriptionProperty: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      teamDescriptionProperty: (value: string, values: ToadieFormValues) =>
        !values.registryEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      flattenDomains: (value: boolean, values: ToadieFormValues) =>
        !values.registryEnabled || value ? null : t("toadie.validation.flattenDomains"),
    },
  };
}

export function fromToadieConnection(row: ToadieConnection): ToadieFormValues {
  const registryMapping = row.registryMapping;
  return {
    name: row.name,
    baseUrl: row.baseUrl,
    browserUrl: row.browserUrl,
    apiKey: "",
    enabled: row.enabled,
    refreshIntervalMinutes: row.refreshIntervalMinutes,
    ...row.mapping,
    registryEnabled: registryMapping != null,
    registryMapping: registryMapping == null ? DEFAULT_REGISTRY_MAPPING : {
      domainBlueprint: registryMapping.domainBlueprint,
      systemDomainRelation: registryMapping.systemDomainRelation ?? "",
      domainParentRelation: registryMapping.domainParentRelation ?? "",
      flattenDomains: registryMapping.flattenDomains,
      domainDescriptionProperty: registryMapping.domainDescriptionProperty ?? "",
      systemDescriptionProperty: registryMapping.systemDescriptionProperty ?? "",
      teamDescriptionProperty: registryMapping.teamDescriptionProperty ?? "",
    },
  };
}

export function toToadieRequest(values: ToadieFormValues): ToadieConnectionBody {
  const optionalIdentifier = (value: string) => value.trim() || null;
  return {
    name: values.name.trim(),
    baseUrl: values.baseUrl.trim().replace(/\/$/, ""),
    browserUrl: values.browserUrl.trim().replace(/\/$/, ""),
    enabled: values.enabled,
    refreshIntervalMinutes: Number(values.refreshIntervalMinutes),
    mapping: {
      serviceBlueprint: values.serviceBlueprint.trim(),
      apiBlueprint: values.apiBlueprint.trim(),
      providesRelation: values.providesRelation.trim(),
      consumesRelation: values.consumesRelation.trim(),
      systemRelation: values.systemRelation.trim(),
    },
    registryMapping: values.registryEnabled ? {
      domainBlueprint: values.registryMapping.domainBlueprint.trim(),
      systemDomainRelation: optionalIdentifier(values.registryMapping.systemDomainRelation),
      domainParentRelation: optionalIdentifier(values.registryMapping.domainParentRelation),
      flattenDomains: true,
      domainDescriptionProperty: optionalIdentifier(values.registryMapping.domainDescriptionProperty),
      systemDescriptionProperty: optionalIdentifier(values.registryMapping.systemDescriptionProperty),
      teamDescriptionProperty: optionalIdentifier(values.registryMapping.teamDescriptionProperty),
    } : null,
    ...(values.apiKey.trim() ? { apiKey: values.apiKey.trim() } : {}),
  };
}

export function toadieSaveErrorMessage(error: unknown, t: TFunction): string {
  if (error instanceof ApiError && error.status === 400 && error.detail) return error.detail;
  return saveErrorMessage(error, t, {
    forbidden: "toadie.error.adminOnly",
    conflict: "toadie.error.conflict",
    invalid: "toadie.error.invalid",
    notFound: "toadie.error.gone",
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
