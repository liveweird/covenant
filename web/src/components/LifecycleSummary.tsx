import { Button, SimpleGrid, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { LifecycleAttention, LifecycleOverviewSummary } from "../api/lifecycleOverview";

export default function LifecycleSummary({ summary, selected, onSelect }: { summary: LifecycleOverviewSummary; selected?: LifecycleAttention; onSelect: (attention?: LifecycleAttention) => void }) {
  const { t } = useTranslation();
  const cards: Array<{ attention: LifecycleAttention; label: string; count: number }> = [
    { attention: "DEADLINE_SOON", label: t("lifecycleOverview.summary.deadlines30"), count: summary.deadlineSoon },
    { attention: "SUPPORT_ENDED", label: t("lifecycleOverview.summary.supportEnded"), count: summary.supportEnded },
    { attention: "MIGRATION_INCOMPLETE", label: t("lifecycleOverview.summary.incompletePlan"), count: summary.migrationIncomplete },
    { attention: "USAGE_UNCERTAIN", label: t("lifecycleOverview.summary.usageUnavailable"), count: summary.usageUncertain },
  ];
  return <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
    {cards.map((card) => <Button key={card.attention} variant={selected === card.attention ? "light" : "default"} h="auto" py="sm"
      aria-pressed={selected === card.attention} onClick={() => onSelect(selected === card.attention ? undefined : card.attention)}>
      <Stack gap={0} align="center">
        <Text size="xl" fw={700}>{card.count}</Text>
        <Text size="sm">{card.label}</Text>
      </Stack>
    </Button>)}
  </SimpleGrid>;
}
