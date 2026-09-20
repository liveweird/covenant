import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Alert, Button, Group, Modal, Select, Stack, Text, Textarea, TextInput } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { updateReleaseLine, type ReleaseLineResponse, type ReleaseLineUpdateBody, type SupportStatus } from "../api/releaseLines";
import { listAllVersions } from "../api/versions";
import { saveErrorMessage } from "../utils/saveError";
import { parseSemver } from "../utils/semver";
import { SUPPORT_STATUSES, supportStatusLabel } from "../utils/releaseLines";
import { showSuccessToast } from "../utils/toast";
import ReleaseLineReplacementFields from "./ReleaseLineReplacementFields";
import RetirementImpactModal from "./RetirementImpactModal";

export default function ReleaseLinePolicyModal({ contractId, line, onClose }: { contractId: number; line: ReleaseLineResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<SupportStatus>(line.supportStatus);
  const [endsOn, setEndsOn] = useState(line.supportEndsOn ?? "");
  const [policy, setPolicy] = useState(line.supportPolicy ?? "");
  const [recommendedId, setRecommendedId] = useState<string | null>(line.recommendedVersionId == null ? null : String(line.recommendedVersionId));
  const [deprecatesOn, setDeprecatesOn] = useState(line.deprecatesOn ?? "");
  const [guide, setGuide] = useState(line.migrationGuide ?? "");
  const [replacement, setReplacement] = useState(line.replacement ?? null);
  const [reviewing, setReviewing] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const versions = useQuery({
    queryKey: ["contracts", "versions", contractId, "all", line.major],
    queryFn: () => listAllVersions(contractId, line.major),
  });
  const candidates = (versions.data ?? []).filter((version) => version.lifecycle === "ACTIVE" && parseSemver(version.version)?.prerelease === null);

  const body: ReleaseLineUpdateBody = {
      deprecatesOn: deprecatesOn || null,
      replacementContractId: replacement?.contractId ?? null,
      replacementMajor: replacement?.major ?? null,
      migrationGuide: guide.trim() || null,
      supportStatus: status,
      supportEndsOn: endsOn || null,
      supportPolicy: policy.trim() || null,
      recommendedVersionId: status === "END_OF_LIFE" || recommendedId == null ? null : Number(recommendedId),
    };

  async function save() {
    setSubmitting(true);
    setError(null);
    try {
      await updateReleaseLine(contractId, line.major, body);
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      showSuccessToast(t("contracts.releaseLines.toast.saved"));
      onClose();
    } catch (err) {
      setError(saveErrorMessage(err, t, {
        forbidden: "contracts.saveForbidden",
        notFound: "contracts.saveGone",
        conflict: "contracts.releaseLines.saveConflict",
        invalid: "contracts.releaseLines.saveInvalid",
        failedStatus: "common.error.saveFailedStatus",
        failed: "common.error.saveFailedNetwork",
      }));
      setSubmitting(false);
    }
  }

  const invalidReplacement = replacement?.contractId === contractId && (replacement.major == null || replacement.major === line.major);
  const invalidDates = Boolean(deprecatesOn && endsOn && deprecatesOn > endsOn);
  const lineName = `${line.major}.x`;
  if (reviewing) return <RetirementImpactModal contractId={contractId} major={line.major}
    plan={{ ...line, ...body, deprecatesOn: body.deprecatesOn ?? null, supportEndsOn: body.supportEndsOn ?? null, migrationGuide: body.migrationGuide ?? null, replacement }}
    canWrite onClose={() => setReviewing(false)} onConfirm={() => void save()}
    actionDescription={t("contracts.releaseLines.endSupportHint")} confirmLabel={t("contracts.releaseLines.endSupport")} pending={submitting} error={error} />;
  return (
    <Modal
      opened
      onClose={onClose}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      title={t("contracts.releaseLines.editTitle", { line: lineName })}
      centered size="lg"
    >
      <Stack>
        <Select
          label={t("contracts.releaseLines.supportStatus")}
          data={SUPPORT_STATUSES.map((value) => ({ value, label: supportStatusLabel(value, t) }))}
          value={status}
          onChange={(value) => {
            const next = (value ?? "UNSPECIFIED") as SupportStatus;
            setStatus(next);
            if (next === "END_OF_LIFE") setRecommendedId(null);
          }}
          allowDeselect={false}
        />
        <Text size="sm" c="dimmed">{t("contracts.releaseLines.planHint")}</Text>
        <TextInput label={t("contracts.releaseLines.deprecatesOn")} type="date" value={deprecatesOn} onChange={(event) => setDeprecatesOn(event.currentTarget.value)} />
        <TextInput label={t("contracts.releaseLines.supportEndsOn")} error={invalidDates ? t("contracts.releaseLines.dateOrder") : undefined} type="date" value={endsOn} onChange={(event) => setEndsOn(event.currentTarget.value)} />
        <Textarea label={t("contracts.releaseLines.supportPolicy")} autosize minRows={3} maxLength={2000} value={policy} onChange={(event) => setPolicy(event.currentTarget.value)} />
        <ReleaseLineReplacementFields sourceContractId={contractId} sourceMajor={line.major} value={replacement} onChange={setReplacement} />
        {invalidReplacement && <Alert color="orange">{t("contracts.releaseLines.differentLineRequired")}</Alert>}
        <Textarea label={t("contracts.releaseLines.migrationGuide")} autosize minRows={3} maxLength={8000} value={guide} onChange={(event) => setGuide(event.currentTarget.value)} />
        <Select
          label={t("contracts.releaseLines.recommendedVersion")}
          description={t("contracts.releaseLines.recommendedHint")}
          placeholder={t(status === "END_OF_LIFE" ? "contracts.releaseLines.noRecommendedVersion" : "contracts.releaseLines.automaticRecommendation")}
          data={candidates.map((version) => ({ value: String(version.id), label: version.version }))}
          value={recommendedId}
          onChange={setRecommendedId}
          clearable
          disabled={status === "END_OF_LIFE" || versions.isError}
        />
        {versions.isError && (
          <Alert color="red" variant="light">
            {t("contracts.releaseLines.recommendationsLoadFailed")}
          </Alert>
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
          <Button onClick={() => status === "END_OF_LIFE" && line.supportStatus !== "END_OF_LIFE" ? setReviewing(true) : void save()} loading={submitting} disabled={invalidReplacement || invalidDates}>
            {t("contracts.releaseLines.savePolicy")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
