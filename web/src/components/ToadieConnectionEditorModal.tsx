import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Accordion, Alert, Button, Checkbox, Group, Modal, NumberInput, PasswordInput, Stack, Switch, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { createToadieConnection, updateToadieConnection, type ToadieConnection } from "../api/toadie";
import { applyToadieMappingPreset, EMPTY_TOADIE_FORM, fromToadieConnection, MAX_TOADIE_NAME_LENGTH, toadieFormValidation, toadieSaveErrorMessage, toToadieRequest, type ToadieFormValues } from "../utils/toadieForm";
import { showSuccessToast } from "../utils/toast";

export default function ToadieConnectionEditorModal({ target, expandRegistryMapping = false, onClose, onSaved }: {
  target: ToadieConnection | null;
  expandRegistryMapping?: boolean;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const form = useForm<ToadieFormValues>({
    initialValues: target ? fromToadieConnection(target) : EMPTY_TOADIE_FORM,
    validate: toadieFormValidation(t, target),
  });

  async function save(values: ToadieFormValues) {
    setSubmitting(true);
    setError(null);
    try {
      if (target) {
        await updateToadieConnection(target.id, toToadieRequest(values));
        showSuccessToast(t("toadie.toast.saved"));
      } else {
        await createToadieConnection({ ...toToadieRequest(values), apiKey: values.apiKey.trim() });
        showSuccessToast(t("toadie.toast.created"));
      }
      await onSaved();
    } catch (caught) {
      setError(toadieSaveErrorMessage(caught, t));
      setSubmitting(false);
    }
  }

  return (
    <Modal opened onClose={onClose} closeButtonProps={{ "aria-label": t("common.action.close") }} title={target ? t("toadie.editTitle") : t("toadie.createTitle")} size="lg" centered>
      <form onSubmit={form.onSubmit(save)} noValidate>
        <Stack>
          <TextInput label={t("common.field.name")} maxLength={MAX_TOADIE_NAME_LENGTH} data-autofocus {...form.getInputProps("name")} />
          <TextInput label={t("toadie.field.backendUrl")} description={t("toadie.field.backendUrlHint")} placeholder="https://toadie.internal" disabled={target != null} {...form.getInputProps("baseUrl")} />
          <TextInput label={t("toadie.field.browserUrl")} description={t("toadie.field.browserUrlHint")} placeholder="https://toadie.example.com" {...form.getInputProps("browserUrl")} />
          <PasswordInput label={t("toadie.field.apiKey")} description={target?.hasApiKey ? t("toadie.field.apiKeyKeep") : undefined} autoComplete="new-password" {...form.getInputProps("apiKey")} />
          <Switch label={t("toadie.field.enabled")} {...form.getInputProps("enabled", { type: "checkbox" })} />
          <NumberInput label={t("toadie.field.refreshInterval")} min={1} max={10_080} allowDecimal={false} {...form.getInputProps("refreshIntervalMinutes")} />
          <Accordion variant="contained" defaultValue={expandRegistryMapping ? "registryMapping" : null}>
            <Accordion.Item value="mapping">
              <Accordion.Control>{t("toadie.field.advanced")}</Accordion.Control>
              <Accordion.Panel>
                <Stack gap="sm">
                  <Text size="sm" c="dimmed">{t("toadie.field.mappingHelp")}</Text>
                  <Group gap="xs">
                    <Button type="button" variant="default" size="compact-sm" onClick={() => form.setValues(applyToadieMappingPreset(form.getValues(), "api"))}>{t("toadie.field.useApiMapping")}</Button>
                    <Button type="button" variant="default" size="compact-sm" onClick={() => form.setValues(applyToadieMappingPreset(form.getValues(), "dataset"))}>{t("toadie.field.useDatasetMapping")}</Button>
                  </Group>
                  <TextInput label={t("toadie.field.serviceBlueprint")} {...form.getInputProps("serviceBlueprint")} />
                  <TextInput label={t("toadie.field.apiBlueprint")} {...form.getInputProps("apiBlueprint")} />
                  <TextInput label={t("toadie.field.providesRelation")} {...form.getInputProps("providesRelation")} />
                  <TextInput label={t("toadie.field.consumesRelation")} {...form.getInputProps("consumesRelation")} />
                  <TextInput label={t("toadie.field.systemRelation")} {...form.getInputProps("systemRelation")} />
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
            <Accordion.Item value="registryMapping">
              <Accordion.Control>{t("toadie.registry.configTitle")}</Accordion.Control>
              <Accordion.Panel>
                <Stack gap="sm">
                  <Switch label={t("toadie.registry.enabled")} description={t("toadie.registry.enabledHint")} {...form.getInputProps("registryEnabled", { type: "checkbox" })} />
                  {form.values.registryEnabled && <>
                    <TextInput label={t("toadie.registry.domainBlueprint")} {...form.getInputProps("registryMapping.domainBlueprint")} />
                    <TextInput label={t("toadie.registry.systemDomainRelation")} description={t("toadie.registry.optionalHint")} {...form.getInputProps("registryMapping.systemDomainRelation")} />
                    <TextInput label={t("toadie.registry.domainParentRelation")} description={t("toadie.registry.parentRelationHint")} {...form.getInputProps("registryMapping.domainParentRelation")} />
                    <TextInput label={t("toadie.registry.domainDescriptionProperty")} description={t("toadie.registry.optionalHint")} {...form.getInputProps("registryMapping.domainDescriptionProperty")} />
                    <TextInput label={t("toadie.registry.systemDescriptionProperty")} description={t("toadie.registry.optionalHint")} {...form.getInputProps("registryMapping.systemDescriptionProperty")} />
                    <TextInput label={t("toadie.registry.teamDescriptionProperty")} description={t("toadie.registry.optionalHint")} {...form.getInputProps("registryMapping.teamDescriptionProperty")} />
                    <Alert color="orange" variant="light"><Text size="sm">{t("toadie.registry.flattenWarning")}</Text></Alert>
                    <Checkbox label={t("toadie.registry.flattenAcknowledge")} {...form.getInputProps("registryMapping.flattenDomains", { type: "checkbox" })} />
                  </>}
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
          {error && <Alert color="red" variant="light">{error}</Alert>}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose} disabled={submitting}>{t("common.action.cancel")}</Button>
            <Button type="submit" loading={submitting}>{target ? t("common.action.save") : t("common.action.create")}</Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
