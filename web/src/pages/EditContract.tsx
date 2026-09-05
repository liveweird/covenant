import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Button, Group, Paper, Stack } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getContract, transferContractOwner, updateContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { isAdmin } from "../api/session";
import { listSystems } from "../api/systems";
import ContractFormFields from "../components/ContractFormFields";
import EditPageLoadState from "../components/EditPageLoadState";
import PageHeader from "../components/PageHeader";
import {
  contractDescription,
  contractFormValidation,
  contractSaveErrorMessage,
  EMPTY_CONTRACT_FORM,
  fromContractResponse,
  ownerValueOf,
  splitOwnerValue,
  type ContractFormValues,
} from "../utils/contractForm";
import { contractPath, contractsPath } from "../utils/contractLinks";
import { FORM_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

/**
 * `/contracts/:id/edit`: name and description for any writer; the owner only for an ADMIN
 * (a transfer — its own PUT, sent only when the picker changed). System and type are immutable.
 */
export default function EditContract() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const admin = isAdmin();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });
  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const form = useForm<ContractFormValues>({
    initialValues: EMPTY_CONTRACT_FORM,
    validate: contractFormValidation(t, { withOwner: admin, withSystem: false }),
  });
  if (contract.data && !form.initialized) form.initialize(fromContractResponse(contract.data));

  if (contract.isLoading || contract.isError || !contract.data) {
    const notFound = contract.error instanceof ApiError && contract.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={contract.isLoading}
        message={notFound ? t("contracts.notFound") : loadErrorMessage(contract.error, t)}
        backTo={contractsPath}
        backLabel={t("contracts.backToList")}
      />
    );
  }
  const data = contract.data;
  if (!data.canWrite) {
    return <EditPageLoadState isLoading={false} message={t("contracts.saveForbidden")} backTo={contractPath(id)} backLabel={t("contracts.backToContract")} />;
  }

  async function save(values: ContractFormValues) {
    setError(null);
    setSubmitting(true);
    try {
      await updateContract(id, { name: values.name.trim(), description: contractDescription(values.description) });
      if (admin && values.owner && values.owner !== ownerValueOf(data.owner)) {
        await transferContractOwner(id, splitOwnerValue(values.owner));
      }
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      showSuccessToast(t("contracts.toast.saved"));
      navigate(contractPath(id), { replace: true });
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) form.setFieldError("name", t("contracts.saveConflict"));
      else setError(contractSaveErrorMessage(err, t));
      setSubmitting(false);
    }
  }

  return (
    <Stack gap="md">
      <PageHeader title={t("contracts.editTitle", { name: data.name })} backTo={{ to: contractPath(id), label: t("contracts.backToContract") }} />
      <Paper withBorder p="xl" maw={FORM_MAX_WIDTH}>
        <form onSubmit={form.onSubmit(save)} noValidate>
          <Stack gap="lg">
            <ContractFormFields
              form={form}
              mode="edit"
              systems={systems.data?.items ?? []}
              ownerEditable={admin}
              currentOwner={{ value: ownerValueOf(data.owner), label: data.owner.name }}
            />
            {error && (
              <Alert color="red" variant="light">
                {error}
              </Alert>
            )}
            <Group justify="flex-end" gap="sm">
              <Button type="button" variant="default" onClick={() => navigate(contractPath(id))} disabled={submitting}>
                {t("common.action.cancel")}
              </Button>
              <Button type="submit" loading={submitting}>
                {t("common.action.save")}
              </Button>
            </Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
