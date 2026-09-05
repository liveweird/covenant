import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Divider, Group, Modal, PasswordInput, Select, Stack, Switch, Textarea, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { createEnvironment, updateEnvironment, type EnvironmentResponse } from "../api/environments";
import { ApiError } from "../api/http";
import { charCountDescription } from "../utils/charCount";
import {
  EMPTY_ENVIRONMENT_FORM,
  environmentFormValidation,
  environmentSaveErrorMessage,
  fromEnvironmentResponse,
  isSasl,
  KAFKA_SASL_MECHANISMS,
  KAFKA_SECURITY_PROTOCOLS,
  MAX_ENVIRONMENT_DESCRIPTION_LENGTH,
  MAX_ENVIRONMENT_NAME_LENGTH,
  toEnvironmentRequest,
  type EnvironmentFormValues,
} from "../utils/environmentForm";
import { showSuccessToast } from "../utils/toast";

/**
 * Create / edit an environment: the system, a name, and up to three targets behind switches.
 * Passwords are write-only — on an edit the field starts blank and "leave blank to keep" is the
 * rule (the server keeps the stored one when the request omits it).
 */
export default function EnvironmentEditorModal({
  target,
  systemOptions,
  defaultSystemId,
  onClose,
  onSaved,
}: {
  target: EnvironmentResponse | null;
  systemOptions: { value: string; label: string }[];
  defaultSystemId: string | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<EnvironmentFormValues>({
    initialValues: target ? fromEnvironmentResponse(target) : { ...EMPTY_ENVIRONMENT_FORM, systemId: defaultSystemId },
    validate: environmentFormValidation(t, target),
  });
  const sasl = form.values.kafkaEnabled && isSasl(form.values.securityProtocol);

  async function save(values: EnvironmentFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      if (target) {
        await updateEnvironment(target.id, toEnvironmentRequest(values));
        showSuccessToast(t("environments.toast.saved"));
      } else {
        await createEnvironment(toEnvironmentRequest(values));
        showSuccessToast(t("environments.toast.created"));
      }
      await onSaved();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("environments.saveConflict"));
      else setError(environmentSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal closeButtonProps={{ "aria-label": t("common.action.close") }} opened onClose={onClose} title={target ? t("environments.editTitle") : t("environments.createTitle")} centered size="lg">
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <Select label={t("environments.field.system")} data={systemOptions} searchable allowDeselect={false} {...form.getInputProps("systemId")} />
          <TextInput label={t("common.field.name")} maxLength={MAX_ENVIRONMENT_NAME_LENGTH} data-autofocus {...form.getInputProps("name")} />
          <Textarea
            label={t("common.field.description")}
            autosize
            minRows={2}
            maxLength={MAX_ENVIRONMENT_DESCRIPTION_LENGTH}
            description={charCountDescription(form.values.description.length, MAX_ENVIRONMENT_DESCRIPTION_LENGTH)}
            inputWrapperOrder={["label", "input", "description", "error"]}
            {...form.getInputProps("description")}
          />
          <Divider label={t("environments.target.http")} />
          <Switch label={t("environments.field.httpEnabled")} {...form.getInputProps("httpEnabled", { type: "checkbox" })} />
          {form.values.httpEnabled && (
            <TextInput label={t("environments.field.httpBaseUrl")} description={t("environments.field.httpHint")} placeholder="http://gateway.internal:8080" {...form.getInputProps("httpBaseUrl")} />
          )}
          <Divider label={t("environments.target.kafka")} />
          <Switch label={t("environments.field.kafkaEnabled")} {...form.getInputProps("kafkaEnabled", { type: "checkbox" })} />
          {form.values.kafkaEnabled && (
            <>
              <TextInput label={t("environments.field.bootstrapServers")} placeholder="broker-1:9092,broker-2:9092" {...form.getInputProps("bootstrapServers")} />
              <Select
                label={t("environments.field.securityProtocol")}
                data={KAFKA_SECURITY_PROTOCOLS.map((p) => ({ value: p, label: p }))}
                allowDeselect={false}
                {...form.getInputProps("securityProtocol")}
              />
              {sasl && (
                <>
                  <Select label={t("environments.field.saslMechanism")} data={KAFKA_SASL_MECHANISMS.map((m) => ({ value: m, label: m }))} {...form.getInputProps("saslMechanism")} />
                  <TextInput label={t("environments.field.username")} autoComplete="off" {...form.getInputProps("kafkaUsername")} />
                  <PasswordInput
                    label={t("environments.field.password")}
                    description={target?.kafka?.hasPassword ? t("environments.field.passwordKeep") : undefined}
                    autoComplete="new-password"
                    {...form.getInputProps("kafkaPassword")}
                  />
                </>
              )}
            </>
          )}
          <Divider label={t("environments.target.postgres")} />
          <Switch label={t("environments.field.pgEnabled")} {...form.getInputProps("pgEnabled", { type: "checkbox" })} />
          {form.values.pgEnabled && (
            <>
              <TextInput label={t("environments.field.jdbcUrl")} description={t("environments.field.jdbcHint")} placeholder="jdbc:postgresql://db.internal:5432/app?sslmode=require" {...form.getInputProps("jdbcUrl")} />
              <TextInput label={t("environments.field.pgUsername")} autoComplete="off" {...form.getInputProps("pgUsername")} />
              <PasswordInput
                label={t("environments.field.pgPassword")}
                description={target?.postgres?.hasPassword ? t("environments.field.passwordKeep") : undefined}
                autoComplete="new-password"
                {...form.getInputProps("pgPassword")}
              />
            </>
          )}
          {error && (
            <Alert color="red" variant="light">
              {error}
            </Alert>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={submitting}>
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
