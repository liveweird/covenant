import { Alert, Anchor, Badge, Group, Paper, Stack, Text, type MantineColor } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Link as RouterLink } from "react-router-dom";
import type { CompatibilityDirection, CompatibilityReport, CompatibilityVerdict, VersionBump } from "../api/versions";
import { versionDiffPath } from "../utils/contractLinks";
import FindingsPanel from "./FindingsPanel";

/** Text-first, never colour-only: FULL teal, BACKWARD/FORWARD orange (one direction only), NONE red, UNKNOWN gray. */
const VERDICT_COLOR: Record<CompatibilityVerdict, MantineColor> = {
  FULL: "teal",
  BACKWARD: "orange",
  FORWARD: "orange",
  NONE: "red",
  UNKNOWN: "gray",
};

/** The bump "why" sentence: MAJOR/PRERELEASE/NONE/DOWNGRADE are one fixed sentence each; a
 * MINOR/PATCH bump PROMISES backward compatibility, so it names whether that promise held. */
function bumpSentence(t: TFunction, bump: VersionBump, from: string, to: string, backward: CompatibilityDirection): string {
  switch (bump) {
    case "MAJOR":
      return t("versions.compatibility.bump.MAJOR", { from, to });
    case "PRERELEASE":
      return t("versions.compatibility.bump.PRERELEASE", { from, to });
    case "NONE":
      return t("versions.compatibility.bump.NONE", { from, to });
    case "DOWNGRADE":
      return t("versions.compatibility.bump.DOWNGRADE", { from, to });
    case "MINOR":
    case "PATCH": {
      const base = `versions.compatibility.bump.${bump}` as const;
      if (backward.compatible === true) return t(`${base}.holds`, { from, to });
      if (backward.compatible === false) return t(`${base}.broken`, { from, to, count: backward.findings.length });
      return t(`${base}.unknown`, { from, to });
    }
    default:
      return bump;
  }
}

/** One direction's outcome: the SKIPPED/CHECKER_UNAVAILABLE note when not computable, a plain
 * "Nothing" line when compatible with no facts, else the same findings list stored views use
 * (no jump buttons — there is no editor or reader position to jump to here). */
function DirectionOutcome({ direction }: { direction: CompatibilityDirection }) {
  const { t } = useTranslation();
  if (direction.compatible == null) {
    return (
      <Alert color="gray" variant="light">
        {direction.findings[0]?.message ?? t("versions.compatibility.checkerUnavailable")}
      </Alert>
    );
  }
  if (direction.findings.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {t("versions.compatibility.nothing")}
      </Text>
    );
  }
  return <FindingsPanel findings={direction.findings} mode="stored" />;
}

function FullCard({ report }: { report: CompatibilityReport }) {
  const { t } = useTranslation();
  const to = report.to.version;
  return (
    <Paper withBorder p="md" radius="md" role="region" aria-label={t("versions.compatibility.title")}>
      <Stack gap="md">
        <Group justify="space-between" wrap="wrap">
          <Text fw={600}>{t("versions.compatibility.title")}</Text>
          <Badge color={VERDICT_COLOR[report.verdict]} variant="light">
            {t(`versions.compatibility.verdictBadge.${report.verdict}`)}
          </Badge>
        </Group>
        {report.from ? (
          <>
            <Text size="sm">{t(`versions.compatibility.verdict.${report.verdict}`, { from: report.from.version, to })}</Text>
            {report.bump && (
              <Text size="sm" c="dimmed">
                {bumpSentence(t, report.bump, report.from.version, to, report.backward)}
              </Text>
            )}
            <Stack gap="xs">
              <Text fw={600} size="sm">
                {t("versions.compatibility.breaksTitle", { version: report.from.version })}
              </Text>
              <DirectionOutcome direction={report.backward} />
            </Stack>
            <Stack gap="xs">
              <Text fw={600} size="sm">
                {t("versions.compatibility.breaksTitle", { version: to })}
              </Text>
              <DirectionOutcome direction={report.forward} />
            </Stack>
          </>
        ) : (
          <Alert color="gray" variant="light">
            {t("versions.compatibility.noBaseline")}
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}

function CompactCard({ report, contractId }: { report: CompatibilityReport; contractId: number }) {
  const { t } = useTranslation();
  if (!report.from) {
    return (
      <Text size="sm" c="dimmed">
        {t("versions.compatibility.noBaseline")}
      </Text>
    );
  }
  return (
    <Group gap="xs" wrap="wrap" align="center">
      <Text size="sm">{t("versions.compatibility.compact", { from: report.from.version })}</Text>
      <Badge color={VERDICT_COLOR[report.verdict]} variant="light">
        {t(`versions.compatibility.verdictBadge.${report.verdict}`)}
      </Badge>
      <Anchor component={RouterLink} to={versionDiffPath(contractId, report.from.id, report.to.id)} size="sm">
        {t("versions.compatibility.details")}
      </Anchor>
    </Group>
  );
}

/**
 * The compatibility verdict between a version pair (`GET …/{vid}/compatibility`), computed
 * server-side and handed over settled — this component never fetches on its own. `full` (the
 * Compare page) names both directions with their breaking-change facts and the bump's "why"
 * sentence; `compact` (the version page's side panel, above Findings) is one line with a
 * Details link into the diff.
 */
export default function CompatibilityCard(
  props: { variant: "full"; report: CompatibilityReport } | { variant: "compact"; report: CompatibilityReport; contractId: number },
) {
  return props.variant === "full" ? <FullCard report={props.report} /> : <CompactCard report={props.report} contractId={props.contractId} />;
}
