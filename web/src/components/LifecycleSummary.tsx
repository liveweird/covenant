import { SimpleGrid, Text, UnstyledButton } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { LifecycleAttention, LifecycleOverviewSummary } from "../api/lifecycleOverview";
import classes from "../theme.module.css";

export default function LifecycleSummary({ summary, selected, onSelect }: { summary: LifecycleOverviewSummary; selected?: LifecycleAttention; onSelect: (attention?: LifecycleAttention) => void }) {
  const { t } = useTranslation();
  const cards: Array<{ attention: LifecycleAttention; label: string; count: number }> = [
    { attention: "DEADLINE_SOON", label: t("lifecycleOverview.summary.deadlines30"), count: summary.deadlineSoon },
    { attention: "SUPPORT_ENDED", label: t("lifecycleOverview.summary.supportEnded"), count: summary.supportEnded },
    { attention: "MIGRATION_INCOMPLETE", label: t("lifecycleOverview.summary.incompletePlan"), count: summary.migrationIncomplete },
    { attention: "USAGE_UNCERTAIN", label: t("lifecycleOverview.summary.usageUnavailable"), count: summary.usageUncertain },
  ];
  return <SimpleGrid type="container" cols={{ base: 2, "44rem": 4 }} spacing={1} className={classes.summaryGrid}>
    {cards.map((card) => <UnstyledButton key={card.attention} className={classes.summaryTile}
      aria-pressed={selected === card.attention} onClick={() => onSelect(selected === card.attention ? undefined : card.attention)}>
      <Text component="span" size="lg" fw={700} className={classes.summaryValue}>{card.count}</Text>
      <Text component="span" size="sm" className={classes.summaryLabel}>{card.label}</Text>
    </UnstyledButton>)}
  </SimpleGrid>;
}
