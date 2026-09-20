import { Anchor, Stack, Text } from "@mantine/core";
import { Link } from "react-router-dom";
import { useTranslation } from "react-i18next";
import type { ReleaseLineResponse } from "../api/releaseLines";
import { contractPath } from "../utils/contractLinks";

export default function ReleaseLinePlan({ line, compact = false }: { line: ReleaseLineResponse; compact?: boolean }) {
  const { t } = useTranslation();
  const replacement = line.replacement;
  return <Stack gap="xs">
    {line.deprecatesOn && <Text size="sm">{t("contracts.releaseLines.deprecationDate", { date: line.deprecatesOn })}</Text>}
    {replacement && <Stack gap={2}>
      <Text size="xs" c="dimmed" fw={600}>{t("contracts.releaseLines.replacementContract")}</Text>
      {replacement.available ? <Anchor component={Link} to={contractPath(replacement.contractId)} size="sm">
        {replacement.contractName}{replacement.major != null ? ` · ${replacement.major}.x` : ""}
      </Anchor> : <Text size="sm" c="orange">{t("contracts.releaseLines.replacementUnavailable", { id: replacement.contractId, line: replacement.major == null ? "" : ` · ${replacement.major}.x` })}</Text>}
    </Stack>}
    {line.migrationGuide && <Stack gap={2}>
      <Text size="xs" c="dimmed" fw={600}>{t("contracts.releaseLines.migrationGuide")}</Text>
      <Text size="sm" lineClamp={compact ? 3 : undefined} style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{line.migrationGuide}</Text>
    </Stack>}
  </Stack>;
}
