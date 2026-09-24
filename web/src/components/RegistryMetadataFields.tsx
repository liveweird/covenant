import type { ReactNode } from "react";
import { Alert, Textarea, TextInput, type TextareaProps, type TextInputProps } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { charCountDescription } from "../utils/charCount";

type RegistrySourceLock = { descriptionSynced: boolean };

/** Shared name/description controls and optional Toadie metadata lock notice. */
export default function RegistryMetadataFields({
  source,
  nameMaxLength,
  descriptionMaxLength,
  descriptionLength,
  nameInputProps,
  descriptionInputProps,
  beforeName,
}: {
  source?: RegistrySourceLock | null;
  nameMaxLength: number;
  descriptionMaxLength: number;
  descriptionLength: number;
  nameInputProps: TextInputProps;
  descriptionInputProps: TextareaProps;
  beforeName?: ReactNode;
}) {
  const { t } = useTranslation();

  return (
    <>
      {source && (
        <Alert color="gray" variant="light">
          {t("toadie.registry.metadataLocked")}
          {!source.descriptionSynced && ` ${t("toadie.registry.descriptionLocal")}`}
        </Alert>
      )}
      {beforeName}
      <TextInput
        label={t("common.field.name")}
        maxLength={nameMaxLength}
        data-autofocus
        disabled={Boolean(source)}
        {...nameInputProps}
      />
      <Textarea
        label={t("common.field.description")}
        autosize
        minRows={2}
        maxLength={descriptionMaxLength}
        description={charCountDescription(descriptionLength, descriptionMaxLength)}
        inputWrapperOrder={["label", "input", "description", "error"]}
        disabled={source?.descriptionSynced === true}
        {...descriptionInputProps}
      />
    </>
  );
}
