import { useMemo } from "react";
import { useTranslation } from "react-i18next";
import { useParams, useSearchParams } from "react-router-dom";
import { Alert, Group, Select, Stack, Switch, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { getContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { getVersion, listVersions } from "../api/versions";
import EditPageLoadState from "../components/EditPageLoadState";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import TextDiffView from "../components/TextDiffView";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import { contractPath, contractsPath } from "../utils/contractLinks";
import { collapseUnchanged, diffLines, diffStats } from "../utils/lineDiff";
import { loadErrorMessage } from "../utils/saveError";

/**
 * `/contracts/:id/diff?from=&to=`: two versions of one contract side by side as a line diff
 * (utils/lineDiff.ts — the documents are stored byte-exact, so the diff is the author's own
 * edit). Defaults to the two highest versions; the pickers rewrite the URL so a comparison is
 * shareable. "Hide unchanged" keeps three lines of context around each change.
 */
export default function VersionDiff() {
  const { t } = useTranslation();
  const { id: idParam } = useParams();
  const id = Number(idParam);
  const [params, setParams] = useSearchParams();
  const [hideUnchanged, setHideUnchanged] = useStoredState("versionDiff.hideUnchanged", true, isBoolean);
  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });
  const versions = useQuery({
    queryKey: ["contracts", "versions", id, "all"],
    queryFn: () => listVersions(id, { page: 1, pageSize: 100, sort: "-version" }),
    enabled: Number.isFinite(id),
  });
  const items = versions.data?.items ?? [];
  const toId = Number(params.get("to")) || (items[0]?.id ?? null);
  const fromId = Number(params.get("from")) || (items.find((v) => v.id !== toId)?.id ?? null);
  const from = useQuery({ queryKey: ["contracts", "version", id, fromId], queryFn: () => getVersion(id, fromId as number), enabled: fromId != null });
  const to = useQuery({ queryKey: ["contracts", "version", id, toId], queryFn: () => getVersion(id, toId as number), enabled: toId != null });

  const diff = useMemo(() => (from.data && to.data ? diffLines(from.data.content, to.data.content) : null), [from.data, to.data]);
  const rows = useMemo(() => (diff ? (hideUnchanged ? collapseUnchanged(diff) : diff) : []), [diff, hideUnchanged]);
  const stats = diff ? diffStats(diff) : null;

  if (contract.isLoading || contract.isError || !contract.data) {
    const notFound = contract.error instanceof ApiError && contract.error.status === 404;
    return (
      <EditPageLoadState
        isLoading={contract.isLoading}
        message={notFound ? t("contracts.notFound") : loadErrorMessage(contract.error, t)}
        backTo={contractsPath}
        backLabel={t("contracts.backToList")}
      />
    );
  }
  const options = items.map((v) => ({ value: String(v.id), label: `${v.version} (${t(`versions.lifecycle.${v.lifecycle}`)})` }));
  const pick = (side: "from" | "to", value: string | null) => {
    if (!value) return;
    const next = new URLSearchParams(params);
    next.set(side, value);
    if (fromId != null) next.set("from", side === "from" ? value : String(fromId));
    if (toId != null) next.set("to", side === "to" ? value : String(toId));
    setParams(next, { replace: true });
  };

  return (
    <Stack gap="md">
      <PageHeader
        title={t("versions.diff.title", { name: contract.data.name })}
        description={t("versions.diff.intro")}
        backTo={{ to: contractPath(id), label: t("contracts.backToContract") }}
      />
      <Group align="flex-end" gap="md" wrap="wrap">
        <Select label={t("versions.diff.from")} data={options} value={fromId != null ? String(fromId) : null} onChange={(v) => pick("from", v)} allowDeselect={false} w={240} />
        <Select label={t("versions.diff.to")} data={options} value={toId != null ? String(toId) : null} onChange={(v) => pick("to", v)} allowDeselect={false} w={240} />
        <Switch label={t("versions.diff.hideUnchanged")} checked={hideUnchanged} onChange={(e) => setHideUnchanged(e.currentTarget.checked)} pb={6} />
        {stats && (
          <Text size="sm" c="dimmed" pb={6}>
            {t("versions.diff.stats", { added: stats.added, removed: stats.removed })}
          </Text>
        )}
      </Group>
      {items.length < 2 && versions.data && (
        <Alert color="gray" variant="light">
          {t("versions.diff.needTwo")}
        </Alert>
      )}
      {(from.isError || to.isError) && (
        <Alert color="red" variant="light" title={t("versions.loadFailed")}>
          {loadErrorMessage(from.error ?? to.error, t)}
        </Alert>
      )}
      {diff ? (
        stats && stats.added === 0 && stats.removed === 0 ? (
          <Alert color="teal" variant="light">
            {t("versions.diff.identical")}
          </Alert>
        ) : (
          <TextDiffView rows={rows} label={t("versions.diff.viewAria", { from: from.data?.version ?? "", to: to.data?.version ?? "" })} />
        )
      ) : items.length >= 2 ? (
        <LoadingBlock />
      ) : null}
    </Stack>
  );
}
