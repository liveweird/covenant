import type { TFunction } from "i18next";
import type { ToadieAdoptionKind, ToadieConnection, ToadieConnectionBody } from "../api/toadie";
import { ApiError } from "../api/http";
import { saveErrorMessage } from "./saveError";
import { nameRule } from "./formRules";

export const MAX_TOADIE_NAME_LENGTH = 100;
export const TOADIE_MAPPING_PRESETS = {
  api: {
    serviceBlueprint: "service",
    apiBlueprint: "api",
    providesRelation: "provides_apis",
    consumesRelation: "consumes_apis",
    systemRelation: "system",
  },
  dataset: {
    serviceBlueprint: "service",
    apiBlueprint: "dataset",
    providesRelation: "produces_datasets",
    consumesRelation: "consumes_datasets",
    systemRelation: "system",
  },
} as const;

export const TOADIE_ADOPTION_MAPPING_PRESETS = {
  api: {
    blueprint: "api_adoption",
    kind: "API_MAJOR_LINE" as ToadieAdoptionKind,
    consumerRelation: "consumer",
    targetRelation: "api",
    environmentRelation: "environment",
    valueProperty: "major_line",
    statusProperty: "status",
    declaredByProperty: "declared_by",
    verifiedAtProperty: "verified_at",
    notesProperty: "notes",
  },
  dataset: {
    blueprint: "dataset_adoption",
    kind: "DATASET_CONTRACT_VERSION" as ToadieAdoptionKind,
    consumerRelation: "consumer",
    targetRelation: "dataset",
    environmentRelation: "environment",
    valueProperty: "contract_version",
    statusProperty: "status",
    declaredByProperty: "declared_by",
    verifiedAtProperty: "verified_at",
    notesProperty: "notes",
  },
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

type AdoptionMappingValues = {
  blueprint: string;
  kind: ToadieAdoptionKind;
  consumerRelation: string;
  targetRelation: string;
  environmentRelation: string;
  valueProperty: string;
  statusProperty: string;
  declaredByProperty: string;
  verifiedAtProperty: string;
  notesProperty: string;
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
  adoptionEnabled: boolean;
  adoptionMapping: AdoptionMappingValues;
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
  ...TOADIE_MAPPING_PRESETS.api,
  adoptionEnabled: false,
  adoptionMapping: TOADIE_ADOPTION_MAPPING_PRESETS.api,
  registryEnabled: false,
  registryMapping: DEFAULT_REGISTRY_MAPPING,
};

export function applyToadieMappingPreset(values: ToadieFormValues, preset: keyof typeof TOADIE_MAPPING_PRESETS): ToadieFormValues {
  return { ...values, ...TOADIE_MAPPING_PRESETS[preset] };
}

export function applyToadieAdoptionMappingPreset(values: ToadieFormValues, preset: keyof typeof TOADIE_ADOPTION_MAPPING_PRESETS): ToadieFormValues {
  return { ...values, adoptionMapping: { ...TOADIE_ADOPTION_MAPPING_PRESETS[preset] } };
}

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
    adoptionMapping: {
      blueprint: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      consumerRelation: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      targetRelation: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      environmentRelation: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      valueProperty: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      statusProperty: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      declaredByProperty: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      verifiedAtProperty: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
      notesProperty: (value: string, values: ToadieFormValues) => !values.adoptionEnabled || !value.trim() || IDENTIFIER.test(value.trim()) ? null : t("toadie.validation.mapping"),
    },
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
  const adoptionMapping = row.adoptionMapping;
  return {
    name: row.name,
    baseUrl: row.baseUrl,
    browserUrl: row.browserUrl,
    apiKey: "",
    enabled: row.enabled,
    refreshIntervalMinutes: row.refreshIntervalMinutes,
    ...row.mapping,
    adoptionEnabled: adoptionMapping != null,
    adoptionMapping: adoptionMapping == null ? TOADIE_ADOPTION_MAPPING_PRESETS.api : {
      ...adoptionMapping,
      environmentRelation: adoptionMapping.environmentRelation ?? "",
      statusProperty: adoptionMapping.statusProperty ?? "",
      declaredByProperty: adoptionMapping.declaredByProperty ?? "",
      verifiedAtProperty: adoptionMapping.verifiedAtProperty ?? "",
      notesProperty: adoptionMapping.notesProperty ?? "",
    },
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
    adoptionMapping: values.adoptionEnabled ? {
      blueprint: values.adoptionMapping.blueprint.trim(),
      kind: values.adoptionMapping.kind,
      consumerRelation: values.adoptionMapping.consumerRelation.trim(),
      targetRelation: values.adoptionMapping.targetRelation.trim(),
      environmentRelation: optionalIdentifier(values.adoptionMapping.environmentRelation),
      valueProperty: values.adoptionMapping.valueProperty.trim(),
      statusProperty: optionalIdentifier(values.adoptionMapping.statusProperty),
      declaredByProperty: optionalIdentifier(values.adoptionMapping.declaredByProperty),
      verifiedAtProperty: optionalIdentifier(values.adoptionMapping.verifiedAtProperty),
      notesProperty: optionalIdentifier(values.adoptionMapping.notesProperty),
    } : null,
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
