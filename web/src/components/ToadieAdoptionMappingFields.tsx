import { Button, Group, Select, Stack, Switch, Text, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import { useTranslation } from "react-i18next";
import { applyToadieAdoptionMappingPreset, type ToadieFormValues } from "../utils/toadieForm";

export default function ToadieAdoptionMappingFields({ form }: { form: UseFormReturnType<ToadieFormValues> }) {
  const { t } = useTranslation();
  return <Stack gap="sm">
    <Switch label={t("toadie.adoptionMapping.enabled")} description={t("toadie.adoptionMapping.enabledHint")} {...form.getInputProps("adoptionEnabled", { type: "checkbox" })} />
    {form.values.adoptionEnabled && <>
      <Text size="sm" c="dimmed">{t("toadie.adoptionMapping.help")}</Text>
      <Group gap="xs">
        <Button type="button" variant="default" size="compact-sm" onClick={() => form.setValues(applyToadieAdoptionMappingPreset(form.getValues(), "api"))}>{t("toadie.adoptionMapping.useApi")}</Button>
        <Button type="button" variant="default" size="compact-sm" onClick={() => form.setValues(applyToadieAdoptionMappingPreset(form.getValues(), "dataset"))}>{t("toadie.adoptionMapping.useDataset")}</Button>
      </Group>
      <TextInput label={t("toadie.adoptionMapping.blueprint")} {...form.getInputProps("adoptionMapping.blueprint")} />
      <Select label={t("toadie.adoptionMapping.kind")} allowDeselect={false} data={[
        { value: "API_MAJOR_LINE", label: t("toadie.adoptionMapping.apiMajorLine") },
        { value: "DATASET_CONTRACT_VERSION", label: t("toadie.adoptionMapping.datasetContractVersion") },
      ]} {...form.getInputProps("adoptionMapping.kind")} />
      <TextInput label={t("toadie.adoptionMapping.consumerRelation")} {...form.getInputProps("adoptionMapping.consumerRelation")} />
      <TextInput label={t("toadie.adoptionMapping.targetRelation")} {...form.getInputProps("adoptionMapping.targetRelation")} />
      <TextInput label={t("toadie.adoptionMapping.environmentRelation")} description={t("toadie.adoptionMapping.optional")} {...form.getInputProps("adoptionMapping.environmentRelation")} />
      <TextInput label={t("toadie.adoptionMapping.valueProperty")} {...form.getInputProps("adoptionMapping.valueProperty")} />
      <TextInput label={t("toadie.adoptionMapping.statusProperty")} description={t("toadie.adoptionMapping.optional")} {...form.getInputProps("adoptionMapping.statusProperty")} />
      <TextInput label={t("toadie.adoptionMapping.declaredByProperty")} description={t("toadie.adoptionMapping.optional")} {...form.getInputProps("adoptionMapping.declaredByProperty")} />
      <TextInput label={t("toadie.adoptionMapping.verifiedAtProperty")} description={t("toadie.adoptionMapping.optional")} {...form.getInputProps("adoptionMapping.verifiedAtProperty")} />
      <TextInput label={t("toadie.adoptionMapping.notesProperty")} description={t("toadie.adoptionMapping.optional")} {...form.getInputProps("adoptionMapping.notesProperty")} />
    </>}
  </Stack>;
}
