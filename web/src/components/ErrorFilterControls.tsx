import { useTranslation } from "react-i18next";
import { MultiSelect } from "@mantine/core";
import type { ErrorFacets } from "../api/errors";
import type { ErrorFilterState } from "../hooks/useErrorFilterState";
import { LIFECYCLES } from "../utils/lifecycle";
import ContractFilterControls from "./ContractFilterControls";

/**
 * The Errors report's filter set: the contracts scope (`ContractFilterControls`, its own
 * lifecycle/hasErrors controls hidden via `useContractFilterState(..., { latestVersion: false })`)
 * plus the VERSION's own lifecycle — carrying the facets' per-lifecycle counts (lifted: each
 * option's count applies every OTHER filter, its own dimension lifted).
 */
export default function ErrorFilterControls({ filters, facets = null }: { filters: ErrorFilterState; facets?: ErrorFacets | null }) {
  const { t } = useTranslation();
  const { slots } = filters;
  const lifecycleCounts = facets ? new Map(facets.lifecycle.map((f) => [f.value, f.count])) : null;
  return (
    <>
      <ContractFilterControls filters={filters.base} />
      <MultiSelect
        label={t("errors.filter.lifecycle")}
        placeholder={slots.lifecycles.length === 0 ? t("common.state.any") : undefined}
        data={LIFECYCLES.map((l) => ({ value: l, label: lifecycleCounts ? `${t(`versions.lifecycle.${l}`)} (${lifecycleCounts.get(l) ?? 0})` : t(`versions.lifecycle.${l}`) }))}
        value={slots.lifecycles}
        onChange={slots.setLifecycles}
        clearable
        clearButtonProps={{ "aria-label": t("common.filter.clearLifecycle") }}
        w={280}
      />
    </>
  );
}
