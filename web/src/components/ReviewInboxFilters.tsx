import { Select } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { ReviewInboxFilterState } from "../hooks/useReviewInboxFilters";
import ClearableTextInput from "./ClearableTextInput";

export default function ReviewInboxFilters({ filters }: { filters: ReviewInboxFilterState }) {
  const { t } = useTranslation();
  return <>
    <ClearableTextInput label={t("reviewInbox.filter.search")} value={filters.slots.q} onChange={filters.slots.setQ} clearLabel={t("reviewInbox.filter.clearSearch")} />
    <Select
      label={t("reviewInbox.filter.scope")}
      value={filters.slots.scope}
      onChange={(value) => filters.slots.setScope(value as typeof filters.slots.scope)}
      allowDeselect={false}
      data={[
        { value: "RELATED", label: t("reviewInbox.scope.RELATED") },
        { value: "OWNED", label: t("reviewInbox.scope.OWNED") },
        { value: "FOLLOWED", label: t("reviewInbox.scope.FOLLOWED") },
        { value: "ALL", label: t("reviewInbox.scope.ALL") },
      ]}
    />
  </>;
}
