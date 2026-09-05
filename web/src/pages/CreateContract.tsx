import { useTranslation } from "react-i18next";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { createContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { listSystems } from "../api/systems";
import ContractFormFields from "../components/ContractFormFields";
import PageHeader from "../components/PageHeader";
import { contractFormValidation, contractSaveErrorMessage, EMPTY_CONTRACT_FORM, toContractCreateRequest, type ContractFormValues } from "../utils/contractForm";
import { contractPath, contractsPath } from "../utils/contractLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { showSuccessToast } from "../utils/toast";
import { useState } from "react";

/**
 * `/contracts/new`: the contract record — placement (system, type), identity, owner. No document
 * yet: the first version is added from the contract's page. `?systemId=` preselects the system
 * (the tree's per-system entry point).
 */
export default function CreateContract() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params] = useSearchParams();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const form = useForm<ContractFormValues>({
    initialValues: { ...EMPTY_CONTRACT_FORM, systemId: params.get("systemId") },
    validate: contractFormValidation(t),
  });

  async function save(values: ContractFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      const created = await createContract(toContractCreateRequest(values));
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      await queryClient.invalidateQueries({ queryKey: ["systems"] });
      showSuccessToast(t("contracts.toast.created"));
      navigate(contractPath(created.id), { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("contracts.saveConflict"));
      else setError(contractSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("contracts.createTitle")} description={t("contracts.createIntro")} backTo={{ to: contractsPath, label: t("contracts.backToList") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(save)} noValidate>
          <Stack gap="lg">
            <ContractFormFields form={form} mode="create" systems={systems.data?.items ?? []} ownerEditable />
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Group justify="flex-end" gap="sm">
              <Button type="button" variant="default" onClick={() => navigate(contractsPath)} disabled={submitting}>
                {t("common.action.cancel")}
              </Button>
              <Button type="submit" loading={submitting}>
                {t("common.action.create")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
