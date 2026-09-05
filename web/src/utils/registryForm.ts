import type { TFunction } from "i18next";
import { ApiError, isTimeoutError } from "../api/http";

// Server limits (domains/Domain.kt, systems/System.kt) mirrored client-side.
export const MAX_REGISTRY_NAME_LENGTH = 100;
export const MAX_REGISTRY_DESCRIPTION_LENGTH = 2000;

/** The shape both registries edit: a name and an optional description (systems add a domain). */
export type RegistryFormValues = { name: string; description: string };

export const EMPTY_REGISTRY_FORM: RegistryFormValues = { name: "", description: "" };

/** Validation rules mirrored from the server (`area` picks the i18n vocabulary). */
export function registryFormValidation(t: TFunction, area: "domains" | "systems") {
  return {
    name: (value: string) => {
      const v = value.trim();
      return v.length >= 1 && v.length <= MAX_REGISTRY_NAME_LENGTH ? null : t(`${area}.validation.nameLength`);
    },
    description: (value: string) =>
      value.trim().length <= MAX_REGISTRY_DESCRIPTION_LENGTH ? null : t(`${area}.validation.descriptionLength`),
  };
}

/** A blank description travels as null (the server stores NULL, never ""). */
export function registryDescription(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/** The registry save vocabulary — 409 is about the name field and is handled by the caller. */
export function registrySaveErrorMessage(err: unknown, t: TFunction, area: "domains" | "systems"): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return t(`${area}.saveForbidden`);
    if (err.status === 404) return t(`${area}.saveGone`);
    if (err.status === 400) return t(`${area}.saveInvalid`);
    return t("common.error.saveFailedStatus", { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.saveFailedNetwork");
}
