import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import type { TFunction } from "i18next";
import { Alert, Badge, Button, Checkbox, Group, Modal, Select, Stack, Table, Text } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { ApiError } from "../api/http";
import { listAllDomains } from "../api/domains";
import { listAllSystems } from "../api/systems";
import { listAllTeams } from "../api/teams";
import {
  applyToadieRegistrySync,
  listToadieRegistryCandidates,
  previewToadieRegistrySync,
  type ToadieConnection,
  type ToadieRegistryCandidate,
  type ToadieRegistryKind,
  type ToadieRegistrySyncBody,
  type ToadieRegistrySyncPreview,
  type ToadieRegistrySyncSelection,
} from "../api/toadie";
import ClearableTextInput from "./ClearableTextInput";
import EmptyState from "./EmptyState";
import PaginationBar from "./PaginationBar";
import TableLoadingRow from "./TableLoadingRow";
import { IconDatabaseImport } from "@tabler/icons-react";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const MAX_SELECTION = 50;
type Selection = ToadieRegistrySyncSelection & { title: string };

export default function ToadieRegistrySyncModal({ connection, onClose, onApplied }: {
  connection: ToadieConnection;
  onClose: () => void;
  onApplied: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const [kind, setKind] = useState<ToadieRegistryKind>("DOMAIN");
  const [search, setSearch] = useState("");
  const [debouncedSearch] = useDebouncedValue(search, 300);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [selected, setSelected] = useState<Map<string, Selection>>(new Map());
  const [preview, setPreview] = useState<ToadieRegistrySyncPreview | null>(null);
  const [previewing, setPreviewing] = useState(false);
  const [applying, setApplying] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const candidates = useQuery({
    queryKey: ["toadie", "registryCandidates", connection.id, kind, page, pageSize, debouncedSearch],
    queryFn: async () => ({ kind, page: await listToadieRegistryCandidates(connection.id, { kind, page, pageSize, q: debouncedSearch || undefined }) }),
    placeholderData: keepPreviousData,
  });
  const localDomains = useQuery({ queryKey: ["domains", "all"], queryFn: listAllDomains, enabled: kind !== "TEAM" });
  const localSystems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listAllSystems(), enabled: kind === "SYSTEM" });
  const localTeams = useQuery({ queryKey: ["teams", "all"], queryFn: () => listAllTeams(), enabled: kind === "TEAM" });
  const targetOptions = useMemo(() => {
    const rows = kind === "DOMAIN"
      ? (localDomains.data ?? []).map((row) => ({ value: String(row.id), label: row.name }))
      : kind === "SYSTEM"
        ? (localSystems.data ?? []).map((row) => ({ value: String(row.id), label: `${row.domainName} / ${row.name}` }))
        : (localTeams.data ?? []).map((row) => ({ value: String(row.id), label: row.name }));
    return [{ value: "new", label: t("toadie.registry.importNew") }, ...rows];
  }, [kind, localDomains.data, localSystems.data, localTeams.data, t]);
  const domainOptions = (localDomains.data ?? []).map((row) => ({ value: String(row.id), label: row.name }));
  const body: ToadieRegistrySyncBody = { kind, items: [...selected.values()].map(({ entityId, localId, fallbackDomainId }) => ({
    entityId,
    localId: localId ?? null,
    ...(kind === "SYSTEM" ? { fallbackDomainId: fallbackDomainId ?? null } : {}),
  })) };

  function resetPlan() {
    setPreview(null);
    setActionError(null);
  }

  function chooseKind(value: string | null) {
    if (!value) return;
    setKind(value as ToadieRegistryKind);
    setSelected(new Map());
    setSearch("");
    setPage(1);
    resetPlan();
  }

  function toggle(candidate: ToadieRegistryCandidate, checked: boolean) {
    setSelected((current) => {
      const next = new Map(current);
      if (!checked) next.delete(candidate.entityId);
      else if (next.size < MAX_SELECTION) next.set(candidate.entityId, {
        entityId: candidate.entityId,
        title: candidate.title,
        localId: candidate.linkedLocalId,
        fallbackDomainId: candidate.fallbackDomainId,
      });
      return next;
    });
    resetPlan();
  }

  function updateSelection(entityId: string, change: Partial<Selection>) {
    setSelected((current) => {
      const next = new Map(current);
      const existing = next.get(entityId);
      if (existing) next.set(entityId, { ...existing, ...change });
      return next;
    });
    resetPlan();
  }

  async function loadPreview() {
    setPreviewing(true);
    setActionError(null);
    try {
      setPreview(await previewToadieRegistrySync(connection.id, body));
    } catch (error) {
      setActionError(registryActionError(error, t, "toadie.registry.previewFailed"));
    } finally {
      setPreviewing(false);
    }
  }

  async function apply() {
    if (!preview) return;
    setApplying(true);
    setActionError(null);
    try {
      await applyToadieRegistrySync(connection.id, { ...body, expectedPlanToken: preview.planToken });
      showSuccessToast(t("toadie.toast.registrySynced"));
      await onApplied();
    } catch (error) {
      setActionError(error instanceof ApiError && error.status === 409
        ? t("toadie.registry.stalePreview")
        : registryActionError(error, t, "toadie.registry.applyFailed"));
      setPreview(null);
      setApplying(false);
    }
  }

  const candidatePage = candidates.data?.kind === kind ? candidates.data.page : null;
  const cache = candidatePage?.cache;
  const cacheCurrent = cache?.state === "CURRENT" && !cache.refreshing;
  const busy = previewing || applying;
  const localOptionsError = kind === "DOMAIN" ? localDomains.error : kind === "SYSTEM" ? (localSystems.error ?? localDomains.error) : localTeams.error;
  const localOptionsLoading = kind === "DOMAIN" ? localDomains.isLoading : kind === "SYSTEM" ? (localSystems.isLoading || localDomains.isLoading) : localTeams.isLoading;
  return <Modal opened onClose={() => { if (!busy) onClose(); }} closeButtonProps={{ "aria-label": t("common.action.close") }} title={t("toadie.registry.title", { name: connection.name })} size="xl" centered>
    <Stack gap="md">
      <Text size="sm">{t("toadie.registry.intro")}</Text>
      <Alert color="orange" variant="light">{t("toadie.registry.orderHint")}</Alert>
      <Group align="flex-end">
        <Select label={t("toadie.registry.kind")} value={kind} onChange={chooseKind} disabled={busy} allowDeselect={false} data={[
          { value: "DOMAIN", label: t("toadie.registry.kindDomain") },
          { value: "SYSTEM", label: t("toadie.registry.kindSystem") },
          { value: "TEAM", label: t("toadie.registry.kindTeam") },
        ]} />
        <ClearableTextInput label={t("toadie.registry.search")} value={search} disabled={busy} onChange={(value) => { setSearch(value); setPage(1); }} clearLabel={t("toadie.registry.clearSearch")} />
      </Group>
      {candidates.isError && <Alert color="red" variant="light">{candidates.error instanceof ApiError && candidates.error.status === 409 ? t("toadie.registry.nonCurrent") : loadErrorMessage(candidates.error, t)}</Alert>}
      {localOptionsError && <Alert color="red" variant="light">{loadErrorMessage(localOptionsError, t)}</Alert>}
      {cache && !cacheCurrent && <Alert color="orange" variant="light">{t("toadie.registry.nonCurrent")}</Alert>}
      <Text size="sm" fw={600}>{t("toadie.registry.selected", { count: selected.size })}</Text>
      <Table aria-label={t("toadie.registry.available")}>
        <Table.Thead><Table.Tr><Table.Th style={{ width: 1 }} /><Table.Th>{t("common.field.name")}</Table.Th><Table.Th>{t("toadie.registry.remoteParent")}</Table.Th><Table.Th>{t("toadie.registry.target")}</Table.Th>{kind === "SYSTEM" && <Table.Th>{t("toadie.registry.fallbackDomain")}</Table.Th>}</Table.Tr></Table.Thead>
        <Table.Tbody>
          {candidates.isLoading || candidatePage == null ? <TableLoadingRow colSpan={kind === "SYSTEM" ? 5 : 4} /> : candidatePage.items.length ? candidatePage.items.map((candidate) => {
            const choice = selected.get(candidate.entityId);
            const checked = choice != null;
            return <Table.Tr key={candidate.entityId}>
              <Table.Td><Checkbox aria-label={candidate.title} checked={checked} disabled={busy || localOptionsLoading || (!checked && selected.size >= MAX_SELECTION)} onChange={(event) => toggle(candidate, event.currentTarget.checked)} /></Table.Td>
              <Table.Td><Text size="sm" fw={500}>{candidate.title}</Text><Text size="xs" c="dimmed">{candidate.identifier}</Text>{candidate.issues.map((issue) => <Badge key={issue} color="orange" variant="light" size="xs" mr={4}>{issueLabel(issue, t)}</Badge>)}</Table.Td>
              <Table.Td><Text size="sm" c={candidate.parentTitle ? undefined : "dimmed"}>{candidate.parentTitle ?? "—"}</Text>{candidate.parentIdentifier && <Text size="xs" c="dimmed">{candidate.parentIdentifier}</Text>}</Table.Td>
              <Table.Td><Select aria-label={`${t("toadie.registry.target")} ${candidate.title}`} disabled={!checked || busy || localOptionsLoading} value={choice?.localId == null ? "new" : String(choice.localId)} onChange={(value) => updateSelection(candidate.entityId, { localId: value === "new" || value == null ? null : Number(value) })} data={targetOptions} searchable allowDeselect={false} /></Table.Td>
              {kind === "SYSTEM" && <Table.Td>{candidate.parentEntityId == null ? <Select aria-label={`${t("toadie.registry.fallbackDomain")} ${candidate.title}`} disabled={!checked || busy || localDomains.isLoading || localDomains.isError} value={choice?.fallbackDomainId == null ? null : String(choice.fallbackDomainId)} onChange={(value) => updateSelection(candidate.entityId, { fallbackDomainId: value == null ? null : Number(value) })} data={domainOptions} searchable clearable /> : <Text size="sm" c="dimmed">—</Text>}</Table.Td>}
            </Table.Tr>;
          }) : !candidates.isError ? <Table.Tr><Table.Td colSpan={kind === "SYSTEM" ? 5 : 4}><EmptyState icon={IconDatabaseImport} label={t("toadie.registry.noCandidates")} /></Table.Td></Table.Tr> : null}
        </Table.Tbody>
      </Table>
      <PaginationBar total={candidatePage?.total ?? 0} page={page} pageSize={pageSize} onPageChange={(next) => { if (!busy) setPage(next); }} onPageSizeChange={(size) => { if (!busy) { setPageSize(size); setPage(1); } }} />
      {selected.size >= MAX_SELECTION && <Alert color="orange" variant="light">{t("toadie.registry.selectionLimit")}</Alert>}
      {preview && <PreviewTable preview={preview} domainNames={new Map((localDomains.data ?? []).map((domain) => [domain.id, domain.name]))} />}
      {preview && !preview.canApply && <Alert color="orange" variant="light">{t("toadie.registry.cannotApply")}</Alert>}
      {actionError && <Alert color="red" variant="light">{actionError}</Alert>}
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose} disabled={busy}>{t("common.action.cancel")}</Button>
        <Button variant="default" onClick={() => void loadPreview()} loading={previewing} disabled={selected.size === 0 || applying || !cacheCurrent || localOptionsLoading || Boolean(localOptionsError)}>{t("toadie.registry.preview")}</Button>
        <Button onClick={() => void apply()} loading={applying} disabled={!preview?.canApply || previewing}>{t("toadie.registry.apply")}</Button>
      </Group>
    </Stack>
  </Modal>;
}

function PreviewTable({ preview, domainNames }: { preview: ToadieRegistrySyncPreview; domainNames: ReadonlyMap<number, string> }) {
  const { t } = useTranslation();
  return <Stack gap="xs" role="region" aria-label={t("toadie.registry.previewTitle")}>
    <Text fw={600}>{t("toadie.registry.previewTitle")}</Text>
    <Table><Table.Thead><Table.Tr><Table.Th>{t("toadie.registry.actionLabel")}</Table.Th><Table.Th>{t("toadie.registry.before")}</Table.Th><Table.Th>{t("toadie.registry.after")}</Table.Th></Table.Tr></Table.Thead>
      <Table.Tbody>{preview.items.map((item) => <Table.Tr key={item.entityId}><Table.Td><Badge variant="light">{t(actionKey(item.action))}</Badge>{item.issues.map((issue) => <Text key={issue} size="xs" c="orange">{issueLabel(issue, t)}</Text>)}</Table.Td><Table.Td>{recordSummary(item.before, domainNames, t)}</Table.Td><Table.Td>{recordSummary(item.after, domainNames, t)}</Table.Td></Table.Tr>)}</Table.Tbody>
    </Table>
  </Stack>;
}

function recordSummary(value: { name: string; description: string | null; domainId: number | null } | null, domainNames: ReadonlyMap<number, string>, t: TFunction) {
  if (!value) return "—";
  return <Stack gap={0}><Text size="sm">{value.name}</Text>{value.description && <Text size="xs" c="dimmed">{value.description}</Text>}{value.domainId != null && <Text size="xs" c="dimmed">{domainNames.has(value.domainId) ? t("toadie.registry.previewDomain", { name: domainNames.get(value.domainId) }) : t("toadie.registry.previewDomainId", { id: value.domainId })}</Text>}</Stack>;
}

function actionKey(value: "IMPORT" | "LINK" | "UPDATE") {
  if (value === "IMPORT") return "toadie.registry.actionImport" as const;
  if (value === "LINK") return "toadie.registry.actionLink" as const;
  return "toadie.registry.actionUpdate" as const;
}

function issueLabel(issue: string, t: TFunction) {
  return t(`toadie.registry.issue.${issue}`, { defaultValue: issue });
}

function registryActionError(error: unknown, t: TFunction, fallback: "toadie.registry.previewFailed" | "toadie.registry.applyFailed") {
  return saveErrorMessage(error, t, { forbidden: "toadie.error.adminOnly", conflict: "toadie.registry.stalePreview", invalid: "toadie.error.invalid", failedStatus: "common.error.actionFailedStatus", failed: fallback });
}
