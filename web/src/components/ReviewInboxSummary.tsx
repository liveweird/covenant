import { Button, SimpleGrid, Stack, Text } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ReviewInboxAttention, ReviewInboxSummary as Summary } from "../api/reviewInbox";

export default function ReviewInboxSummary({ summary, selected, onSelect }: { summary?: Summary; selected?: ReviewInboxAttention; onSelect: (attention?: ReviewInboxAttention) => void }) {
  const { t } = useTranslation();
  const cards: Array<{ attention?: ReviewInboxAttention; label: string; count?: number }> = [
    { label: t("reviewInbox.summary.all"), count: summary?.total },
    { attention: "AWAITING_MY_REVIEW", label: t("reviewInbox.summary.awaiting"), count: summary?.awaitingMyReview },
    { attention: "CHANGES_REQUESTED", label: t("reviewInbox.summary.changes"), count: summary?.changesRequested },
    { attention: "NEEDS_NEW_REVIEW", label: t("reviewInbox.summary.needsNew"), count: summary?.needsNewReview },
  ];
  return <SimpleGrid cols={{ base: 1, sm: 2, lg: 4 }}>
    {cards.map((card) => {
      const pressed = card.attention == null ? selected == null : selected === card.attention;
      return <Button key={card.attention ?? "ALL"} variant={pressed ? "light" : "default"} h="auto" py="sm" aria-label={card.label} aria-pressed={pressed} onClick={() => onSelect(card.attention)}>
        <Stack gap={0} align="center">
          <Text size="xl" fw={700}>{card.count ?? "—"}</Text>
          <Text size="sm">{card.label}</Text>
        </Stack>
      </Button>;
    })}
  </SimpleGrid>;
}
