import { useState } from "react";
import { Alert, Button, Checkbox, Group, Modal, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getReleaseLine, type ReleaseLineResponse } from "../api/releaseLines";
import { loadErrorMessage } from "../utils/saveError";
import ContractToadieUsage from "./ContractToadieUsage";
import ReleaseLinePlan from "./ReleaseLinePlan";
import LoadingBlock from "./LoadingBlock";

export default function RetirementImpactModal({ contractId, major, plan, canWrite, onClose, onConfirm, confirmLabel, pending = false, error, actionDescription }: {
  contractId: number; major: number; plan?: ReleaseLineResponse; canWrite: boolean; onClose: () => void;
  onConfirm?: () => void; confirmLabel?: string; pending?: boolean; error?: string | null; actionDescription?: string;
}) {
  const { t } = useTranslation();
  const [acknowledged, setAcknowledged] = useState(false);
  const [usageReady, setUsageReady] = useState(false);
  const policy = useQuery({ queryKey: ["contracts", "release-line", contractId, major],
    queryFn: () => getReleaseLine(contractId, major), enabled: plan == null });
  const line = plan ?? policy.data;
  return <Modal opened size="xl" onClose={() => { if (!pending) onClose(); }}
    closeButtonProps={{ "aria-label": t("common.action.close") }} title={t("contracts.releaseLines.impactTitle", { line: `${major}.x` })}>
    <Stack>
      {!plan && policy.isPending && <LoadingBlock />}
      {!plan && policy.isError && <Alert color="red">{loadErrorMessage(policy.error, t)}<Button variant="default" size="xs" onClick={() => void policy.refetch()}>{t("contracts.releaseLines.retryPlan")}</Button></Alert>}
      {line && <>
        <Text size="sm">{t("contracts.releaseLines.supportEndDate", { date: line.supportEndsOn ?? t("contracts.releaseLines.noSupportEnd") })}</Text>
        <ReleaseLinePlan line={line} />
        {!line.migrationGuide && <Text c="dimmed" size="sm">{t("contracts.releaseLines.noMigrationGuide")}</Text>}
      </>}
      <ContractToadieUsage contractId={contractId} canWrite={canWrite} consumersOnly onReviewReady={setUsageReady} />
      {actionDescription && <Text fw={500}>{actionDescription}</Text>}
      {onConfirm && <Checkbox label={t("contracts.releaseLines.acknowledgeImpact")} checked={acknowledged}
        onChange={(event) => setAcknowledged(event.currentTarget.checked)} disabled={pending} />}
      {error && <Alert color="red">{error}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={pending}>{t(onConfirm ? "common.action.cancel" : "common.action.close")}</Button>
        {onConfirm && <Button color="orange" onClick={onConfirm} loading={pending}
          disabled={!acknowledged || !usageReady || (!plan && !policy.isSuccess)}>{confirmLabel}</Button>}
      </Group>
    </Stack>
  </Modal>;
}
