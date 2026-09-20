import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Badge, Button, Group, Modal, Paper, Select, SimpleGrid, Stack, Text, Textarea, TextInput } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconFilter, IconPlus, IconSettings } from "@tabler/icons-react";
import {
  listAllReleaseLines,
  updateReleaseLine,
  type ReleaseLineResponse,
  type ReleaseLineUpdateBody,
  type SupportStatus,
} from "../api/releaseLines";
import { listAllVersions } from "../api/versions";
import { newVersionPath, versionPath } from "../utils/contractLinks";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { parseSemver } from "../utils/semver";
import { SUPPORT_STATUSES, supportStatusLabel } from "../utils/releaseLines";
import { showSuccessToast } from "../utils/toast";
import EmptyState from "./EmptyState";
import LifecyclePill from "./LifecyclePill";
import LoadingBlock from "./LoadingBlock";

const STATUS_COLORS: Record<SupportStatus, string> = {
  UNSPECIFIED: "gray",
  SUPPORTED: "teal",
  MAINTENANCE: "orange",
  END_OF_LIFE: "red",
};

export default function ReleaseLinesPanel({
  contractId,
  canWrite,
  onFilter,
}: {
  contractId: number;
  canWrite: boolean;
  onFilter: (major: number) => void;
}) {
  const { t } = useTranslation();
  const [editing, setEditing] = useState<ReleaseLineResponse | null>(null);
  const lines = useQuery({
    queryKey: ["contracts", "release-lines", contractId],
    queryFn: () => listAllReleaseLines(contractId),
  });

  return (
    <Stack component="section" aria-label={t("contracts.releaseLines.title")} gap="sm">
      <Text fw={600} size="lg">
        {t("contracts.releaseLines.title")}
      </Text>
      {lines.isLoading && !lines.data && <LoadingBlock />}
      {lines.isError && (
        <Alert color="red" variant="light" title={t("contracts.releaseLines.loadFailed")}>
          {loadErrorMessage(lines.error, t)}
        </Alert>
      )}
      {lines.data?.length === 0 && !lines.isError && <EmptyState icon={IconSettings} label={t("contracts.releaseLines.empty")} />}
      {lines.data && lines.data.length > 0 && (
        <SimpleGrid cols={{ base: 1, lg: 2 }}>
          {lines.data.map((line) => (
            <ReleaseLineCard key={line.id} line={line} canWrite={canWrite} onEdit={() => setEditing(line)} onFilter={() => onFilter(line.major)} />
          ))}
        </SimpleGrid>
      )}
      {editing && <ReleaseLinePolicyModal contractId={contractId} line={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function SummaryLink({ contractId, version }: { contractId: number; version: NonNullable<ReleaseLineResponse["latestVersion"]> }) {
  return (
    <Group gap={6} wrap="nowrap">
      <Anchor component={RouterLink} to={versionPath(contractId, version.id)} size="sm" ff="monospace">
        {version.version}
      </Anchor>
      <LifecyclePill lifecycle={version.lifecycle} size="xs" />
    </Group>
  );
}

function ReleaseLineCard({
  line,
  canWrite,
  onEdit,
  onFilter,
}: {
  line: ReleaseLineResponse;
  canWrite: boolean;
  onEdit: () => void;
  onFilter: () => void;
}) {
  const { t } = useTranslation();
  const name = `${line.major}.x`;
  return (
    <Paper component="section" aria-label={t("contracts.releaseLines.lineAria", { line: name })} withBorder p="md" radius="md">
      <Stack gap="sm">
        <Group justify="space-between" align="center">
          <Text fw={700}>{name}</Text>
          <Badge color={STATUS_COLORS[line.supportStatus]} variant="light">
            {supportStatusLabel(line.supportStatus, t)}
          </Badge>
        </Group>
        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="xs">
          <LineValue label={t("contracts.releaseLines.latestVersion")}>
            {line.latestVersion ? <SummaryLink contractId={line.contractId} version={line.latestVersion} /> : t("contracts.releaseLines.noVersions")}
          </LineValue>
          <LineValue label={t("contracts.releaseLines.recommendedVersion")}>
            {line.recommendedVersion ? <SummaryLink contractId={line.contractId} version={line.recommendedVersion} /> : t("contracts.releaseLines.noRecommendedVersion")}
          </LineValue>
          <LineValue label={t("contracts.releaseLines.supportEndsOn")}>
            {line.supportEndsOn ?? t("contracts.releaseLines.noSupportEnd")}
          </LineValue>
          <LineValue label={t("contracts.releaseLines.versionCount")}>
            {t("contracts.releaseLines.versionCountValue", { count: line.versionCount })}
          </LineValue>
        </SimpleGrid>
        <LineValue label={t("contracts.releaseLines.supportPolicy")}>
          {line.supportPolicy ?? t("contracts.releaseLines.noSupportPolicy")}
        </LineValue>
        <Group gap="xs">
          <Button variant="default" size="xs" leftSection={<IconFilter size={14} />} onClick={onFilter}>
            {t("contracts.releaseLines.filterVersions", { line: name })}
          </Button>
          {canWrite && (
            <Button
              component={RouterLink}
              to={newVersionPath(line.contractId, line.latestVersion?.id, line.major)}
              variant="default"
              size="xs"
              leftSection={<IconPlus size={14} />}
            >
              {t("contracts.releaseLines.newVersion", { line: name })}
            </Button>
          )}
          {canWrite && (
            <Button variant="default" size="xs" leftSection={<IconSettings size={14} />} onClick={onEdit}>
              {t("contracts.releaseLines.editPolicy", { line: name })}
            </Button>
          )}
        </Group>
      </Stack>
    </Paper>
  );
}

function LineValue({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed" fw={600}>
        {label}
      </Text>
      {typeof children === "string" || typeof children === "number" ? <Text size="sm">{children}</Text> : children}
    </Stack>
  );
}

function ReleaseLinePolicyModal({ contractId, line, onClose }: { contractId: number; line: ReleaseLineResponse; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<SupportStatus>(line.supportStatus);
  const [endsOn, setEndsOn] = useState(line.supportEndsOn ?? "");
  const [policy, setPolicy] = useState(line.supportPolicy ?? "");
  const [recommendedId, setRecommendedId] = useState<string | null>(line.recommendedVersionId == null ? null : String(line.recommendedVersionId));
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const versions = useQuery({
    queryKey: ["contracts", "versions", contractId, "all", line.major],
    queryFn: () => listAllVersions(contractId, line.major),
  });
  const candidates = (versions.data ?? []).filter((version) => version.lifecycle === "ACTIVE" && parseSemver(version.version)?.prerelease === null);

  async function save() {
    setSubmitting(true);
    setError(null);
    const body: ReleaseLineUpdateBody = {
      supportStatus: status,
      supportEndsOn: endsOn || null,
      supportPolicy: policy.trim() || null,
      recommendedVersionId: status === "END_OF_LIFE" || recommendedId == null ? null : Number(recommendedId),
    };
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

  const lineName = `${line.major}.x`;
  return (
    <Modal
      opened
      onClose={onClose}
      closeButtonProps={{ "aria-label": t("common.action.close") }}
      title={t("contracts.releaseLines.editTitle", { line: lineName })}
      centered
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
        <TextInput label={t("contracts.releaseLines.supportEndsOn")} type="date" value={endsOn} onChange={(event) => setEndsOn(event.currentTarget.value)} />
        <Textarea label={t("contracts.releaseLines.supportPolicy")} autosize minRows={3} maxLength={2000} value={policy} onChange={(event) => setPolicy(event.currentTarget.value)} />
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
          <Button onClick={() => void save()} loading={submitting}>
            {t("contracts.releaseLines.savePolicy")}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
