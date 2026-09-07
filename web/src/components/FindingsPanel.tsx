import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ActionIcon, Alert, Badge, Chip, Code, Group, Stack, Text, Tooltip } from "@mantine/core";
import { IconCircleCheck, IconCrosshair } from "@tabler/icons-react";
import type { Finding, FindingSource, Severity } from "../api/versions";
import { isHardFinding } from "../api/versions";
import { SEVERITIES, SEVERITY_COLOR } from "../utils/findings";

const SOURCES = [
  "SYNTAX",
  "SCHEMA",
  "SEMANTIC",
  "LINT",
  "BREAKING",
  "CONFORMANCE",
  "SYSTEM",
  "INFERENCE",
] as const satisfies readonly FindingSource[];

/**
 * The check report beside the editor (and under a stored version): the findings with
 * severity/source chip filters and a jump-to-position button per positioned finding. The
 * `live` mode waits for the first answer before declaring the document clean; a stored report
 * also flags a check the sidecar sat out (`checkComplete=false`) so the reader knows the lint
 * verdict is missing, not favourable.
 */
export default function FindingsPanel({
  findings,
  mode,
  checked = true,
  checkComplete = true,
  baselineVersion = null,
  onJump,
  jumpBy = "line",
}: {
  findings: readonly Finding[];
  mode: "live" | "stored";
  /** Live mode: whether a check has answered yet. */
  checked?: boolean;
  checkComplete?: boolean;
  /** Live mode: the ACTIVE version the document was compared against for breaking changes, when one applied. */
  baselineVersion?: string | null;
  /** Called with a positioned finding — the editor scrolls to it. Omitted for a text-less view. */
  onJump?: (finding: Finding) => void;
  /** What the jump button targets: the editor's line (Source view) or the reader element at the finding's path. */
  jumpBy?: "line" | "path";
}) {
  const { t } = useTranslation();
  const [severities, setSeverities] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);
  const counts = { ERROR: 0, WARN: 0, INFO: 0 } as Record<Severity, number>;
  for (const f of findings) counts[f.severity]++;
  const presentSources = SOURCES.filter((s) => findings.some((f) => f.source === s));
  const visible = findings.filter(
    (f) => (severities.length === 0 || severities.includes(f.severity)) && (sources.length === 0 || sources.includes(f.source)),
  );

  return (
    <Stack gap="sm" aria-label={t("findings.title")} role="region">
      <Group justify="space-between" align="center" wrap="wrap" gap="xs">
        <Text fw={600} size="sm">
          {t("findings.title")}
        </Text>
        <Group gap={6} wrap="nowrap">
          {SEVERITIES.map((s) => (
            <Badge key={s} color={counts[s] > 0 ? SEVERITY_COLOR[s] : "gray"} variant="light" size="sm">
              {t(`findings.count.${s}`, { count: counts[s] })}
            </Badge>
          ))}
        </Group>
      </Group>
      {baselineVersion && (
        <Text size="xs" c="dimmed">
          {t("findings.baseline", { version: baselineVersion })}
        </Text>
      )}
      {!checkComplete && (
        <Alert color="orange" variant="light" title={t("findings.incompleteTitle")}>
          {t("findings.incompleteBody")}
        </Alert>
      )}
      {findings.length > 0 && (
        <Group gap="xs" wrap="wrap">
          <Chip.Group multiple value={severities} onChange={setSeverities}>
            <Group gap={4}>
              {SEVERITIES.map((s) => (
                <Chip key={s} value={s} size="xs" color={SEVERITY_COLOR[s]} disabled={counts[s] === 0}>
                  {t(`findings.severity.${s}`)}
                </Chip>
              ))}
            </Group>
          </Chip.Group>
          {presentSources.length > 1 && (
            <Chip.Group multiple value={sources} onChange={setSources}>
              <Group gap={4}>
                {presentSources.map((s) => (
                  <Chip key={s} value={s} size="xs" color="gray">
                    {t(`findings.source.${s}`)}
                  </Chip>
                ))}
              </Group>
            </Chip.Group>
          )}
        </Group>
      )}
      {findings.length === 0 ? (
        <Group gap={6} c="dimmed">
          {mode === "live" && !checked ? (
            <Text size="sm">{t("findings.waiting")}</Text>
          ) : (
            <>
              <IconCircleCheck size={16} color="var(--mantine-color-teal-light-color)" />
              <Text size="sm">{t("findings.clean")}</Text>
            </>
          )}
        </Group>
      ) : (
        <Stack gap={6} role="list" aria-label={t("findings.listAria")}>
          {visible.map((f, index) => (
            <Group key={`${f.code}-${f.line ?? ""}-${f.path ?? ""}-${index}`} role="listitem" gap="xs" wrap="nowrap" align="flex-start">
              <Badge color={SEVERITY_COLOR[f.severity]} variant="light" size="xs" style={{ flexShrink: 0, marginTop: 2 }}>
                {isHardFinding(f) ? t("findings.hard") : t(`findings.severity.${f.severity}`)}
              </Badge>
              <Stack gap={0} style={{ flex: 1, minWidth: 0 }}>
                <Group gap={6} wrap="nowrap">
                  <Text size="xs" c="dimmed">
                    {t(`findings.source.${f.source}`)}
                  </Text>
                  <Code fz="xs">{f.code}</Code>
                </Group>
                <Text size="sm" style={{ overflowWrap: "anywhere" }}>
                  {f.message}
                </Text>
                {f.path && (
                  <Text size="xs" c="dimmed" ff="monospace" truncate>
                    {f.path}
                  </Text>
                )}
              </Stack>
              {jumpBy === "line" && f.line != null && onJump && (
                <Tooltip label={t("findings.jumpTo", { line: f.line, column: f.column ?? 1 })}>
                  <ActionIcon size="sm" aria-label={t("findings.jumpTo", { line: f.line, column: f.column ?? 1 })} onClick={() => onJump(f)}>
                    <IconCrosshair size={14} />
                  </ActionIcon>
                </Tooltip>
              )}
              {jumpBy === "path" && f.path && onJump && (
                <Tooltip label={t("findings.jumpToElement")}>
                  <ActionIcon size="sm" aria-label={t("findings.jumpToElement")} onClick={() => onJump(f)}>
                    <IconCrosshair size={14} />
                  </ActionIcon>
                </Tooltip>
              )}
            </Group>
          ))}
          {visible.length === 0 && (
            <Text size="sm" c="dimmed">
              {t("findings.noneMatch")}
            </Text>
          )}
        </Stack>
      )}
    </Stack>
  );
}
