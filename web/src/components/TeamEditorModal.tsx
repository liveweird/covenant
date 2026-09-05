import { useState } from "react";
import { Alert, Button, Group, Modal, Stack, Textarea, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { ApiError } from "../api/http";
import { createTeam, updateTeam, type TeamResponse } from "../api/teams";
import { charCountDescription } from "../utils/charCount";
import {
  EMPTY_TEAM_FORM,
  MAX_TEAM_DESCRIPTION_LENGTH,
  MAX_TEAM_NAME_LENGTH,
  teamFormValidation,
  teamSaveErrorMessage,
  toTeamBody,
  toTeamFormValues,
  type TeamFormValues,
} from "../utils/teamForm";
import { showSuccessToast } from "../utils/toast";

/**
 * Create (target null) / edit (target set) — one modal, the same field block (the registry
 * shape from Toadie's Labels page). The roster is managed on the details page, not here.
 */
export default function TeamEditorModal({
  target,
  onClose,
  onSaved,
}: {
  target: TeamResponse | null;
  onClose: () => void;
  onSaved: (saved: TeamResponse | null) => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const form = useForm<TeamFormValues>({
    initialValues: target ? toTeamFormValues(target) : EMPTY_TEAM_FORM,
    validate: teamFormValidation(t),
  });

  async function save(values: TeamFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      let saved: TeamResponse | null = null;
      if (target) {
        await updateTeam(target.id, toTeamBody(values));
        showSuccessToast(t("teams.toast.saved"));
      } else {
        saved = await createTeam(toTeamBody(values));
        showSuccessToast(t("teams.toast.created"));
      }
      await onSaved(saved);
    } catch (err) {
      // The 409 is about THIS control (the case-insensitive name clash) — mark the field.
      if (err instanceof ApiError && err.status === 409) {
        form.setFieldError("name", t("teams.saveConflict"));
      } else {
        setError(teamSaveErrorMessage(err, t));
      }
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={onClose} title={target ? t("teams.editTitle") : t("teams.createTitle")} centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput
            label={t("common.field.name")}
            maxLength={MAX_TEAM_NAME_LENGTH}
            data-autofocus
            {...form.getInputProps("name")}
          />
          <Textarea
            label={t("common.field.description")}
            autosize
            minRows={2}
            maxLength={MAX_TEAM_DESCRIPTION_LENGTH}
            description={charCountDescription(form.values.description.length, MAX_TEAM_DESCRIPTION_LENGTH)}
            inputWrapperOrder={["label", "input", "description", "error"]}
            {...form.getInputProps("description")}
          />
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Group justify="flex-end" gap="sm">
            <Button type="button" variant="default" onClick={onClose} disabled={submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button type="submit" loading={submitting}>
              {target ? t("common.action.save") : t("common.action.create")}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
