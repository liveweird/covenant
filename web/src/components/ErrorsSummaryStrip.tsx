import { useTranslation } from "react-i18next";
import { Chip, Group, Paper, Text } from "@mantine/core";
import type { ErrorFacets } from "../api/errors";
import type { FindingSource, Severity } from "../api/versions";
import { SEVERITIES, SEVERITY_COLOR, STORED_SOURCES } from "../utils/findings";
import classes from "../theme.module.css";

function countsByValue(rows: readonly { value: string; count: number }[] | undefined): ReadonlyMap<string, number> {
  return new Map((rows ?? []).map((r) => [r.value, r.count]));
}

/**
 * The Errors report's summary strip: three stat tiles (contracts/versions/findings — the
 * totals with every filter applied) and, pushed right, the severity and source chip groups.
 * Unlike Toadie's client-side error-class pills, these chips ARE server filters: toggling one
 * changes the `severity`/`source` params and refetches both queries. Each chip's count comes
 * from the facets with its OWN dimension lifted, so picking a value never hides another from
 * view; the count is `aria-hidden` so a chip's accessible name stays the bare label
 * (`findings.severity.ERROR` = "Error", `findings.source.LINT` = "Lint") for tests and e2e.
 */
export default function ErrorsSummaryStrip({
  facets,
  severities,
  setSeverities,
  sources,
  setSources,
}: {
  facets: ErrorFacets | undefined;
  severities: readonly Severity[];
  setSeverities: (next: string[]) => void;
  sources: readonly FindingSource[];
  setSources: (next: string[]) => void;
}) {
  const { t } = useTranslation();
  const severityCounts = countsByValue(facets?.severity);
  const sourceCounts = countsByValue(facets?.source);
  const tile = (value: number | string, label: string, color?: string) => (
    <Paper withBorder radius="md" px="md" py={6} data-tile={label}>
      <Text size="lg" fw={700} lh={1.2} c={color} style={{ fontVariantNumeric: "tabular-nums" }}>
        {value}
      </Text>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
    </Paper>
  );
  return (
    <Group gap="sm" wrap="wrap" align="stretch" style={{ width: "100%" }}>
      {tile(facets?.contracts ?? "—", t("errors.summary.contracts"))}
      {tile(facets?.versions ?? "—", t("errors.summary.versions"))}
      {/* The AA-tested inks, not a bare hue: red-6/teal-6 text on the surface fails 4.5:1 (the axe sweep pins it). */}
      {tile(
        facets?.findings ?? "—",
        t("errors.summary.findings"),
        facets ? (facets.findings > 0 ? "var(--covenant-ink-error)" : "var(--mantine-color-teal-light-color)") : undefined,
      )}
      <Group gap="md" ml="auto" wrap="wrap" align="center">
        <Chip.Group multiple value={[...severities]} onChange={setSeverities}>
          <Group gap={4} role="group" aria-label={t("errors.severityLabel")}>
            {SEVERITIES.map((s) => (
              <Chip key={s} value={s} size="xs" color={SEVERITY_COLOR[s]}>
                <span>{t(`findings.severity.${s}`)}</span>
                <span aria-hidden="true" className={classes.chipCount} data-zero={(severityCounts.get(s) ?? 0) === 0 || undefined}>
                  {severityCounts.get(s) ?? 0}
                </span>
              </Chip>
            ))}
          </Group>
        </Chip.Group>
        <Chip.Group multiple value={[...sources]} onChange={setSources}>
          <Group gap={4} role="group" aria-label={t("errors.sourceLabel")}>
            {STORED_SOURCES.map((s) => (
              <Chip key={s} value={s} size="xs" color="gray">
                <span>{t(`findings.source.${s}`)}</span>
                <span aria-hidden="true" className={classes.chipCount} data-zero={(sourceCounts.get(s) ?? 0) === 0 || undefined}>
                  {sourceCounts.get(s) ?? 0}
                </span>
              </Chip>
            ))}
          </Group>
        </Chip.Group>
      </Group>
    </Group>
  );
}
