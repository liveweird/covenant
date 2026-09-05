import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import { registryDescription, registryFormValidation, registrySaveErrorMessage } from "./registryForm";

const t = i18n.t;

describe("registryForm", () => {
  test("validation mirrors the server's lengths for both areas", () => {
    for (const area of ["domains", "systems"] as const) {
      const rules = registryFormValidation(t, area);
      expect(rules.name("")).not.toBeNull();
      expect(rules.name("x".repeat(101))).not.toBeNull();
      expect(rules.name("Payments")).toBeNull();
      expect(rules.description("d".repeat(2001))).not.toBeNull();
      expect(rules.description("")).toBeNull();
    }
  });

  test("a blank description is null", () => {
    expect(registryDescription("  ")).toBeNull();
    expect(registryDescription(" x ")).toBe("x");
  });

  test("save errors map to the area's vocabulary", () => {
    expect(registrySaveErrorMessage(new ApiError(403, null), t, "domains")).toBe(t("domains.saveForbidden"));
    expect(registrySaveErrorMessage(new ApiError(404, null), t, "systems")).toBe(t("systems.saveGone"));
    expect(registrySaveErrorMessage(new ApiError(400, null), t, "systems")).toBe(t("systems.saveInvalid"));
    expect(registrySaveErrorMessage(new ApiError(500, null), t, "domains")).toBe("Save failed (500)");
    expect(registrySaveErrorMessage(new DOMException("t", "TimeoutError"), t, "domains")).toBe(t("common.error.timeout"));
    expect(registrySaveErrorMessage(new Error("net"), t, "domains")).toBe(t("common.error.saveFailedNetwork"));
  });
});
