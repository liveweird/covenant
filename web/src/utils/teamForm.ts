import type { TFunction } from "i18next";
import { ApiError, isTimeoutError } from "../api/http";
import type { TeamCreateBody, TeamResponse, TeamUpdateBody } from "../api/teams";

// Server limits (teams/Team.kt) mirrored client-side.
export const MAX_TEAM_NAME_LENGTH = 100;
export const MAX_TEAM_DESCRIPTION_LENGTH = 500;

export type TeamFormValues = {
  name: string;
  description: string;
};

export const EMPTY_TEAM_FORM: TeamFormValues = { name: "", description: "" };

export function toTeamFormValues(team: TeamResponse): TeamFormValues {
  return { name: team.name, description: team.description ?? "" };
}

/** Validation rules shared by the create and edit modal (mirrors the server's checks). */
export function teamFormValidation(t: TFunction) {
  return {
    name: (value: string) => {
      const v = value.trim();
      return v.length >= 1 && v.length <= MAX_TEAM_NAME_LENGTH ? null : t("teams.validation.nameLength");
    },
    description: (value: string) =>
      value.trim().length <= MAX_TEAM_DESCRIPTION_LENGTH ? null : t("teams.validation.descriptionLength"),
  };
}

/** The wire body: a blank description travels as null (the server stores NULL, never ""). */
export function toTeamBody(values: TeamFormValues): TeamCreateBody & TeamUpdateBody {
  const description = values.description.trim();
  return { name: values.name.trim(), description: description === "" ? null : description };
}

/** The team save vocabulary — 409 is about the name field and is handled by the caller. */
export function teamSaveErrorMessage(err: unknown, t: TFunction): string {
  if (err instanceof ApiError) {
    if (err.status === 403) return t("teams.saveForbidden");
    if (err.status === 404) return t("teams.saveGone");
    if (err.status === 400) return t("teams.saveInvalid");
    return t("common.error.saveFailedStatus", { status: err.status });
  }
  if (isTimeoutError(err)) return t("common.error.timeout");
  return t("common.error.saveFailedNetwork");
}
