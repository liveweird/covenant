import { useEffect, useRef, useState } from "react";
import { Alert, Button, Checkbox, Group, Modal, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { getReleaseLine, getReleaseLineMigrationReport, type ReleaseLineResponse } from "../api/releaseLines";
import { loadErrorMessage } from "../utils/saveError";
import { downloadText } from "../utils/document";
import { migrationReportFileName, migrationReportMarkdown } from "../utils/migrationReport";
import { ApiError } from "../api/http";
import { getSessionSnapshot, isSameSessionIdentity } from "../api/session";
import ContractToadieUsage from "./ContractToadieUsage";
import ReleaseLinePlan from "./ReleaseLinePlan";
import LoadingBlock from "./LoadingBlock";

interface RetirementImpactModalProps {
  contractId: number; major: number; plan?: ReleaseLineResponse; canWrite: boolean; onClose: () => void;
  onConfirm?: () => void; confirmLabel?: string; pending?: boolean; error?: string | null; actionDescription?: string;
}

export default function RetirementImpactModal(props: RetirementImpactModalProps) {
  return <RetirementImpactModalContent key={`${props.contractId}:${props.major}`} {...props} />;
}

function RetirementImpactModalContent({ contractId, major, plan, canWrite, onClose, onConfirm, confirmLabel, pending = false, error, actionDescription }: RetirementImpactModalProps) {
  const { t } = useTranslation();
  const [acknowledged, setAcknowledged] = useState(false);
  const [usageReady, setUsageReady] = useState(false);
  const [reportPending, setReportPending] = useState(false);
  const [reportError, setReportError] = useState<"failed" | "tooLarge" | null>(null);
  const reportRequest = useRef(0);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; reportRequest.current += 1; };
  }, []);
  const policy = useQuery({ queryKey: ["contracts", "release-line", contractId, major],
    queryFn: () => getReleaseLine(contractId, major), enabled: plan == null });
  const line = plan ?? policy.data;
  async function downloadReport() {
    const request = ++reportRequest.current;
    const session = getSessionSnapshot();
    setReportPending(true);
    setReportError(null);
    try {
      const report = await getReleaseLineMigrationReport(contractId, major);
      if (!mounted.current || reportRequest.current !== request || !isSameSessionIdentity(session, getSessionSnapshot())) return;
      downloadText(migrationReportFileName(report), migrationReportMarkdown(report, t), "text/markdown;charset=utf-8");
    } catch (error) {
      if (mounted.current && reportRequest.current === request && isSameSessionIdentity(session, getSessionSnapshot())) {
        setReportError(error instanceof ApiError && error.status === 409 ? "tooLarge" : "failed");
      }
    } finally {
      if (mounted.current && reportRequest.current === request) setReportPending(false);
    }
  }
  function close() {
    reportRequest.current += 1;
    setReportPending(false);
    if (!pending) onClose();
  }
  return <Modal opened size="xl" onClose={close}
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
      <Text size="xs" c="dimmed">{t("contracts.releaseLines.reportHint")}</Text>
      {reportError && <Alert color="red">{t(reportError === "tooLarge" ? "contracts.releaseLines.downloadReportTooLarge" : "contracts.releaseLines.downloadReportFailed")}</Alert>}
      {actionDescription && <Text fw={500}>{actionDescription}</Text>}
      {onConfirm && <Checkbox label={t("contracts.releaseLines.acknowledgeImpact")} checked={acknowledged}
        onChange={(event) => setAcknowledged(event.currentTarget.checked)} disabled={pending} />}
      {error && <Alert color="red">{error}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={() => void downloadReport()} loading={reportPending} disabled={pending}>{t("contracts.releaseLines.downloadReport")}</Button>
        <Button variant="default" onClick={close} disabled={pending}>{t(onConfirm ? "common.action.cancel" : "common.action.close")}</Button>
        {onConfirm && <Button color="orange" onClick={onConfirm} loading={pending}
          disabled={!acknowledged || !usageReady || (!plan && !policy.isSuccess)}>{confirmLabel}</Button>}
      </Group>
    </Stack>
  </Modal>;
}
