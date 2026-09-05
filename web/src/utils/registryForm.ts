import type { TFunction } from "i18next";
import { descriptionRule, nameRule } from "./formRules";
import { saveErrorMessage } from "./saveError";

// Server limits (domains/Domain.kt, systems/System.kt) mirrored client-side.
export const MAX_REGISTRY_NAME_LENGTH = 100;
export const MAX_REGISTRY_DESCRIPTION_LENGTH = 2000;

/** The shape both registries edit: a name and an optional description (systems add a domain). */
export type RegistryFormValues = { name: string; description: string };

export const EMPTY_REGISTRY_FORM: RegistryFormValues = { name: "", description: "" };

/** Validation rules mirrored from the server (`area` picks the i18n vocabulary). */
export function registryFormValidation(t: TFunction, area: "domains" | "systems") {
  return {
    name: nameRule(t, `${area}.validation.nameLength`, MAX_REGISTRY_NAME_LENGTH),
    description: descriptionRule(t, `${area}.validation.descriptionLength`, MAX_REGISTRY_DESCRIPTION_LENGTH),
  };
}

/** A blank description travels as null (the server stores NULL, never ""). */
export function registryDescription(value: string): string | null {
  const trimmed = value.trim();
  return trimmed === "" ? null : trimmed;
}

/** The registry save vocabulary — 409 is about the name field and is handled by the caller. */
export function registrySaveErrorMessage(err: unknown, t: TFunction, area: "domains" | "systems"): string {
  return saveErrorMessage(err, t, {
    forbidden: `${area}.saveForbidden`,
    notFound: `${area}.saveGone`,
    invalid: `${area}.saveInvalid`,
    failedStatus: "common.error.saveFailedStatus",
    failed: "common.error.saveFailedNetwork",
  });
}
