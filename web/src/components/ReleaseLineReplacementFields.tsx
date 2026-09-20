import { useState } from "react";
import { Alert, Pagination, Select, Stack } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { useTranslation } from "react-i18next";
import { listContracts } from "../api/contracts";
import { listAllReleaseLines, type ReleaseLineResponse } from "../api/releaseLines";
import { loadErrorMessage } from "../utils/saveError";

type Replacement = ReleaseLineResponse["replacement"];

export default function ReleaseLineReplacementFields({ sourceContractId, sourceMajor, value, onChange }: {
  sourceContractId: number; sourceMajor: number; value: Replacement; onChange: (value: Replacement) => void;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState("");
  const [query] = useDebouncedValue(search, 300);
  const [page, setPage] = useState(1);
  const contracts = useQuery({ queryKey: ["contracts", "replacement-choices", query, page],
    queryFn: () => listContracts({ page, pageSize: 20, q: query || undefined, sort: "name" }) });
  const lines = useQuery({ queryKey: ["contracts", "release-lines", value?.contractId],
    queryFn: () => listAllReleaseLines(value!.contractId), enabled: value != null });
  const choices = (contracts.data?.items ?? []).map((c) => ({ value: String(c.id), label: `${c.name} · ${c.system.name}` }));
  if (value && !choices.some((c) => c.value === String(value.contractId))) {
    choices.unshift({ value: String(value.contractId), label: value.contractName ?? t("contracts.releaseLines.replacementUnavailable", { id: value.contractId, line: "" }) });
  }
  const majors = (lines.data ?? []).filter((line) => value?.contractId !== sourceContractId || line.major !== sourceMajor)
    .map((line) => ({ value: String(line.major), label: `${line.major}.x` }));
  if (value?.major != null && !majors.some((m) => m.value === String(value.major))) majors.unshift({ value: String(value.major), label: `${value.major}.x` });
  return <Stack gap="xs">
    <Select label={t("contracts.releaseLines.replacementContract")} description={t("contracts.releaseLines.replacementHint")}
      searchable clearable searchValue={search} onSearchChange={(q) => { setSearch(q); setPage(1); }}
      data={choices} value={value ? String(value.contractId) : null} onChange={(id) => {
        if (!id) { onChange(null); return; }
        if (Number(id) === value?.contractId) return;
        const selected = contracts.data?.items.find((c) => c.id === Number(id));
        onChange({ contractId: Number(id), contractName: selected?.name ?? null, major: null, available: true });
      }} />
    {(contracts.data?.total ?? 0) > 20 && <Pagination size="xs" aria-label={t("contracts.releaseLines.replacementPages")}
      value={page} onChange={setPage} total={Math.ceil((contracts.data?.total ?? 0) / 20)} />}
    {value && <Select label={t("contracts.releaseLines.replacementLine")} data={majors} value={value.major == null ? null : String(value.major)}
      placeholder={t("contracts.releaseLines.anyReplacementLine")} clearable onChange={(major) => onChange({ ...value, major: major == null ? null : Number(major) })}
      description={value.contractId === sourceContractId ? t("contracts.releaseLines.differentLineRequired") : undefined} />}
    {(contracts.isError || lines.isError) && <Alert color="red">{loadErrorMessage(contracts.error ?? lines.error, t)}</Alert>}
  </Stack>;
}
