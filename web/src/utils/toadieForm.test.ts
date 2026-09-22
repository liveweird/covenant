import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { applyToadieMappingPreset, EMPTY_TOADIE_FORM, fromToadieConnection, TOADIE_MAPPING_PRESETS, toadieFormValidation, toadieSaveErrorMessage, toToadieRequest } from "./toadieForm";

const CONNECTION = {
  id: 1,
  name: "Architecture",
  baseUrl: "https://toadie.internal",
  browserUrl: "https://toadie.example.com",
  enabled: true,
  refreshIntervalMinutes: 60,
  mapping: { serviceBlueprint: "service", apiBlueprint: "api", providesRelation: "provides_apis", consumesRelation: "consumes_apis", systemRelation: "system" },
  hasApiKey: true,
  createdAt: 1,
  updatedAt: 2,
  lastAttemptAt: null,
  lastSuccessAt: null,
  refreshing: false,
  lastErrorCode: null,
  stale: false,
};

describe("Toadie connection form", () => {
  test("accepts qualified Port blueprint and relation identifiers", () => {
    const validation = toadieFormValidation(i18n.t, null);
    const qualified = "@catalog/api:v1=public";
    expect(validation.serviceBlueprint(qualified)).toBeNull();
    expect(validation.apiBlueprint(qualified)).toBeNull();
    expect(validation.providesRelation(qualified)).toBeNull();
    expect(validation.consumesRelation(qualified)).toBeNull();
    expect(validation.systemRelation(qualified)).toBeNull();
    expect(validation.serviceBlueprint(`${qualified}?`)).toBe("Use 1–100 letters, digits or @ _ . : / = -");
    expect(EMPTY_TOADIE_FORM.serviceBlueprint).toBe("service");
  });

  test("mirrors URL, interval, and write-only key validation", () => {
    const create = toadieFormValidation(i18n.t, null);
    expect(create.baseUrl("https://user@example.com")).toBe("Use an absolute http(s) URL without credentials, query or fragment");
    expect(create.browserUrl("https://example.com/path?tab=1")).toBe("Use an absolute http(s) URL without credentials, query or fragment");
    expect(create.refreshIntervalMinutes(0)).toBe("Use a whole number from 1 to 10080");
    expect(create.refreshIntervalMinutes(60)).toBeNull();
    expect(create.apiKey("")).toBe("An API key is required");
    expect(toadieFormValidation(i18n.t, CONNECTION).apiKey("")).toBeNull();
  });

  test("round-trips a response while omitting an unchanged secret from the update body", () => {
    const values = fromToadieConnection(CONNECTION);
    expect(values.apiKey).toBe("");
    expect(toToadieRequest({ ...values, name: " Architecture ", browserUrl: "https://toadie.example.com/" })).toEqual({
      name: "Architecture",
      baseUrl: "https://toadie.internal",
      browserUrl: "https://toadie.example.com",
      enabled: true,
      refreshIntervalMinutes: 60,
      mapping: CONNECTION.mapping,
      registryMapping: null,
    });
    expect(toToadieRequest({ ...values, apiKey: " replacement " })).toHaveProperty("apiKey", "replacement");
  });

  test("mapping presets replace only the five ontology fields", () => {
    const custom = {
      ...EMPTY_TOADIE_FORM,
      name: "Dataset catalog",
      apiKey: "secret",
      registryEnabled: true,
      registryMapping: { ...EMPTY_TOADIE_FORM.registryMapping, flattenDomains: true },
      serviceBlueprint: "custom-service",
      apiBlueprint: "custom-contract",
    };

    const dataset = applyToadieMappingPreset(custom, "dataset");

    expect(dataset).toEqual({ ...custom, ...TOADIE_MAPPING_PRESETS.dataset });
    expect(dataset.apiKey).toBe("secret");
    expect(dataset.registryMapping).toBe(custom.registryMapping);
    expect(applyToadieMappingPreset(dataset, "api")).toEqual({ ...custom, ...TOADIE_MAPPING_PRESETS.api });
  });

  test("requires explicit flattening acknowledgement and serializes optional registry mapping fields", () => {
    const values = { ...EMPTY_TOADIE_FORM, registryEnabled: true, registryMapping: { ...EMPTY_TOADIE_FORM.registryMapping } };
    expect(toadieFormValidation(i18n.t, null).registryMapping.flattenDomains(false, values)).toBe("Acknowledge that nested Toadie domains will be flattened");
    expect(toToadieRequest({ ...values, registryMapping: { ...values.registryMapping, flattenDomains: true, systemDomainRelation: "", domainDescriptionProperty: "summary" } }).registryMapping).toEqual({
      domainBlueprint: "domain",
      systemDomainRelation: null,
      domainParentRelation: "parent_domain",
      flattenDomains: true,
      domainDescriptionProperty: "summary",
      systemDescriptionProperty: null,
      teamDescriptionProperty: null,
    });
  });

  test("shows a server validation detail inline", () => {
    expect(toadieSaveErrorMessage(new ApiError(400, { detail: "mapping invalid" }), i18n.t)).toBe("mapping invalid");
  });
});
