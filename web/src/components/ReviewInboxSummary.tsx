import { SimpleGrid, Text, UnstyledButton } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ReviewInboxAttention, ReviewInboxSummary as Summary } from "../api/reviewInbox";
import classes from "../theme.module.css";

export default function ReviewInboxSummary({ summary, selected, onSelect }: { summary?: Summary; selected?: ReviewInboxAttention; onSelect: (attention?: ReviewInboxAttention) => void }) {
  const { t } = useTranslation();
  const cards: Array<{ attention?: ReviewInboxAttention; label: string; count?: number }> = [
    { label: t("reviewInbox.summary.all"), count: summary?.total },
    { attention: "AWAITING_MY_REVIEW", label: t("reviewInbox.summary.awaiting"), count: summary?.awaitingMyReview },
    { attention: "CHANGES_REQUESTED", label: t("reviewInbox.summary.changes"), count: summary?.changesRequested },
    { attention: "NEEDS_NEW_REVIEW", label: t("reviewInbox.summary.needsNew"), count: summary?.needsNewReview },
  ];
  return <SimpleGrid type="container" cols={{ base: 2, "44rem": 4 }} spacing={1} className={classes.summaryGrid}>
    {cards.map((card) => {
      const pressed = card.attention == null ? selected == null : selected === card.attention;
      return <UnstyledButton key={card.attention ?? "ALL"} className={classes.summaryTile} aria-label={card.label} aria-pressed={pressed} onClick={() => onSelect(card.attention)}>
        <Text component="span" size="lg" fw={700} className={classes.summaryValue}>{card.count ?? "—"}</Text>
        <Text component="span" size="sm" className={classes.summaryLabel}>{card.label}</Text>
      </UnstyledButton>;
    })}
  </SimpleGrid>;
}
