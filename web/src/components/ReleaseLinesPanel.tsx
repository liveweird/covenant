import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { Alert, Anchor, Badge, Button, Group, Paper, SimpleGrid, Stack, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconFilter, IconPlus, IconSettings } from "@tabler/icons-react";
import {
  listAllReleaseLines,
  type ReleaseLineResponse,
  type SupportStatus,
} from "../api/releaseLines";
import { newVersionPath, versionPath } from "../utils/contractLinks";
import { loadErrorMessage } from "../utils/saveError";
import { supportStatusLabel } from "../utils/releaseLines";
import ReleaseLinePolicyModal from "./ReleaseLinePolicyModal";
import ReleaseLinePlan from "./ReleaseLinePlan";
import RetirementImpactModal from "./RetirementImpactModal";
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
  const [reviewing, setReviewing] = useState<ReleaseLineResponse | null>(null);
  const [editing, setEditing] = useState<ReleaseLineResponse | null>(null);
  const lines = useQuery({
    queryKey: ["contracts", "release-lines", contractId],
    queryFn: () => listAllReleaseLines(contractId),
  });

  return (
    <Stack component="section" aria-label={t("contracts.releaseLines.title")} gap="sm">
      <Title order={3} size="h4">
        {t("contracts.releaseLines.title")}
      </Title>
      {lines.isLoading && !lines.data && <LoadingBlock />}
      {lines.isError && (
        <Alert color="red" variant="light" title={t("contracts.releaseLines.loadFailed")}>
          {loadErrorMessage(lines.error, t)}
        </Alert>
      )}
      {lines.data?.length === 0 && !lines.isError && <Text size="sm" c="dimmed">{t("contracts.releaseLines.empty")}</Text>}
      {lines.data && lines.data.length > 0 && (
        <SimpleGrid type="container" cols={{ base: 1, "52rem": 2 }}>
          {lines.data.map((line) => (
            <ReleaseLineCard key={line.id} line={line} canWrite={canWrite} onReview={() => setReviewing(line)} onEdit={() => setEditing(line)} onFilter={() => onFilter(line.major)} />
          ))}
        </SimpleGrid>
      )}
      {reviewing && <RetirementImpactModal contractId={contractId} major={reviewing.major} plan={reviewing} canWrite={canWrite} onClose={() => setReviewing(null)} />}
      {editing && <ReleaseLinePolicyModal contractId={contractId} line={editing} onClose={() => setEditing(null)} />}
    </Stack>
  );
}

function SummaryLink({ contractId, version }: { contractId: number; version: NonNullable<ReleaseLineResponse["latestVersion"]> }) {
  return (
    <Group gap={6}>
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
  onReview,
}: {
  line: ReleaseLineResponse;
  canWrite: boolean;
  onEdit: () => void;
  onReview: () => void;
  onFilter: () => void;
}) {
  const { t } = useTranslation();
  const name = `${line.major}.x`;
  return (
    <Paper component="section" aria-label={t("contracts.releaseLines.lineAria", { line: name })} withBorder p="sm" radius="md">
      <Stack gap="sm">
        <Group justify="space-between" align="center">
          <Text fw={700}>{name}</Text>
          <Badge color={STATUS_COLORS[line.supportStatus]} variant="light">
            {supportStatusLabel(line.supportStatus, t)}
          </Badge>
        </Group>
        <SimpleGrid cols={2} spacing="xs">
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
        <ReleaseLinePlan line={line} compact />
        <Group gap="xs">
          <Button variant="default" size="xs" onClick={onReview}>{t("contracts.releaseLines.reviewImpact", { line: name })}</Button>
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
