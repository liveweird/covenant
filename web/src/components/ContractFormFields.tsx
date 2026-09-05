import { useTranslation } from "react-i18next";
import { Fieldset, Select, Stack, Textarea, TextInput } from "@mantine/core";
import type { UseFormReturnType } from "@mantine/form";
import type { SystemResponse } from "../api/systems";
import { charCountDescription } from "../utils/charCount";
import {
  CONTRACT_TYPE_LABEL,
  CONTRACT_TYPES,
  MAX_CONTRACT_DESCRIPTION_LENGTH,
  MAX_CONTRACT_NAME_LENGTH,
  systemOptions,
  type ContractFormValues,
} from "../utils/contractForm";
import OwnerSelect from "./OwnerSelect";

/**
 * The contract's own fields (the create and edit pages share them). The system and the type are
 * immutable once created (a version's document is validated against the type), so the edit
 * mode shows them disabled; the owner is editable at create, and at edit only through an
 * ADMIN's transfer (the page decides and passes `ownerEditable`).
 */
export default function ContractFormFields({
  form,
  mode,
  systems,
  ownerEditable,
  currentOwner,
}: {
  form: UseFormReturnType<ContractFormValues>;
  mode: "create" | "edit";
  systems: readonly SystemResponse[];
  ownerEditable: boolean;
  currentOwner?: { value: string; label: string } | null;
}) {
  const { t } = useTranslation();
  const edit = mode === "edit";
  return (
    <Stack gap="lg">
      <Fieldset legend={t("contracts.section.placement")}>
        <Stack>
          <Select
            label={t("contracts.field.system")}
            description={edit ? t("contracts.field.systemLocked") : t("contracts.field.systemHint")}
            data={systemOptions(systems)}
            searchable
            allowDeselect={false}
            disabled={edit}
            data-autofocus={!edit}
            {...form.getInputProps("systemId")}
          />
          <Select
            label={t("contracts.field.type")}
            description={edit ? t("contracts.field.typeLocked") : t("contracts.field.typeHint")}
            data={CONTRACT_TYPES.map((type) => ({ value: type, label: CONTRACT_TYPE_LABEL[type] }))}
            allowDeselect={false}
            disabled={edit}
            {...form.getInputProps("type")}
          />
        </Stack>
      </Fieldset>
      <Fieldset legend={t("contracts.section.identity")}>
        <Stack>
          <TextInput
            label={t("common.field.name")}
            description={t("contracts.field.nameHint")}
            maxLength={MAX_CONTRACT_NAME_LENGTH}
            data-autofocus={edit}
            {...form.getInputProps("name")}
          />
          <Textarea
            label={t("common.field.description")}
            autosize
            minRows={2}
            maxLength={MAX_CONTRACT_DESCRIPTION_LENGTH}
            description={charCountDescription(form.values.description.length, MAX_CONTRACT_DESCRIPTION_LENGTH)}
            inputWrapperOrder={["label", "input", "description", "error"]}
            {...form.getInputProps("description")}
          />
        </Stack>
      </Fieldset>
      <Fieldset legend={t("contracts.section.ownership")}>
        <OwnerSelect
          value={form.values.owner}
          onChange={(value) => form.setFieldValue("owner", value)}
          error={form.errors.owner as string | undefined}
          disabled={!ownerEditable}
          description={edit ? (ownerEditable ? t("contracts.field.ownerTransferHint") : t("contracts.field.ownerLocked")) : t("contracts.field.ownerHint")}
          current={currentOwner}
        />
      </Fieldset>
    </Stack>
  );
}
