import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Box, Button, Grid, Group, Paper, Stack, Text } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { deleteVersion, getVersion, updateVersionContent } from "../api/versions";
import ConfirmDeleteModal from "../components/ConfirmDeleteModal";
import EditPageLoadState from "../components/EditPageLoadState";
import FindingsPanel from "../components/FindingsPanel";
import LazyCodeEditor, { type JumpRequest } from "../components/LazyCodeEditor";
import LazyContractReader from "../components/LazyContractReader";
import LifecyclePill from "../components/LifecyclePill";
import PageHeader from "../components/PageHeader";
import SaveAnywayModal from "../components/SaveAnywayModal";
import TypeBadge from "../components/TypeBadge";
import VersionHeaderActions from "../components/VersionHeaderActions";
import VersionMetaStrip from "../components/VersionMetaStrip";
import VersionViewToggle from "../components/VersionViewToggle";
import { useDeleteConfirm } from "../hooks/useDeleteConfirm";
import { useDocumentCheck } from "../hooks/useDocumentCheck";
import { useVersionActions } from "../hooks/useVersionActions";
import { useVersionDownload } from "../hooks/useVersionDownload";
import { useVersionSave } from "../hooks/useVersionSave";
import { useVersionView } from "../hooks/useVersionView";
import { contractPath, contractsPath } from "../utils/contractLinks";
import { detectFormat, MAX_DOCUMENT_BYTES, utf8Length } from "../utils/document";
import { toDiagnostics } from "../utils/findingDiagnostics";
import { isContentEditable } from "../utils/lifecycle";
import { loadErrorMessage, saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";
import classes from "../theme.module.css";

/** The two-column split per view: the reader gets more room, its findings aside less. */
const MAIN_SPAN = { reader: 9, source: 8 } as const;
const ASIDE_SPAN = { reader: 3, source: 4 } as const;
const JUMP_BY = { reader: "path", source: "line" } as const;

function editingHintKey(hasHard: boolean, tooLarge: boolean, dirty: boolean) {
  if (hasHard) return "versions.blockedBySyntax" as const;
  if (tooLarge) return "versions.validation.contentTooLarge" as const;
  return dirty ? ("versions.unsaved" as const) : ("versions.noChanges" as const);
}

/**
 * One version (`/contracts/:id/versions/:vid`): the document read-only in the editor with the
 * stored findings beside it; for a writer of a DRAFT/PROPOSED version, Edit switches the same
 * editor to editing with the live check, and Save runs the strict-save → Save-anyway flow.
 * Transitions, recheck and delete act on the STORED text (disabled while editing). Two renderings
 * of the stored document — the Reader (the render model, milestone 4) and the Source (the editor) —
 * behind the header toggle; editing forces Source.
 */
export default function VersionPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { id: idParam, vid: vidParam } = useParams();
  const id = Number(idParam);
  const vid = Number(vidParam);
  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });
  const version = useQuery({ queryKey: ["contracts", "version", id, vid], queryFn: () => getVersion(id, vid), enabled: Number.isFinite(id) && Number.isFinite(vid) });
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string | null>(null);
  const [jump, setJump] = useState<JumpRequest | null>(null);
  const [highlight, setHighlight] = useState<{ path: string; nonce: number } | null>(null);
  const { view, setView } = useVersionView(version.data?.lifecycle, editing);
  const downloads = useVersionDownload();
  const actions = useVersionActions(id, vid);

  const stored = version.data;
  const text = editing ? (draft ?? stored?.content ?? "") : (stored?.content ?? "");
  const type = contract.data?.type ?? "OPENAPI";
  // Naming the contract adds the breaking-change comparison against its highest ACTIVE version
  // (an unparsable URL id is NaN, which the hook's JSON body carries as null — no branch needed).
  const check = useDocumentCheck({ type, content: editing ? text : "", version: stored?.version ?? null, contractId: id });
  const storedFindings = stored?.findings;
  const liveFindings = check.findings;
  const findings = useMemo(() => (editing ? liveFindings : (storedFindings ?? [])), [editing, liveFindings, storedFindings]);
  const diagnostics = useMemo(() => toDiagnostics(findings, text), [findings, text]);
  const dirty = editing && draft != null && draft !== stored?.content;
  const hasHard = editing && check.findings.some((f) => f.source === "SYNTAX");
  const tooLarge = editing && utf8Length(text) > MAX_DOCUMENT_BYTES;

  async function refresh() {
    await queryClient.invalidateQueries({ queryKey: ["contracts"] });
  }
  const save = useVersionSave({
    document: () => ({ type, content: text, version: stored?.version ?? null }),
    saveRequest: (options) => updateVersionContent(id, vid, text, options),
    onSaved: async (_saved, waived) => {
      setEditing(false);
      setDraft(null);
      await refresh();
      showSuccessToast(t(waived ? "versions.toast.savedWithFindings" : "versions.toast.saved"));
    },
    errorKeys: { forbidden: "contracts.saveForbidden", notFound: "versions.gone", conflict: "versions.contentLocked", invalid: "versions.saveInvalid", failedStatus: "common.error.saveFailedStatus", failed: "common.error.saveFailedNetwork" },
  });
  const remove = useDeleteConfirm<{ id: number; version: string }>({
    mutationFn: (row) => deleteVersion(id, row.id),
    onSuccess: async () => {
      await refresh();
      navigate(contractPath(id), { replace: true });
    },
    successMessage: t("versions.toast.deleted"),
  });

  if (contract.isLoading || version.isLoading || contract.isError || version.isError || !contract.data || !stored) {
    const err = contract.error ?? version.error;
    const notFound = err instanceof ApiError && err.status === 404;
    return (
      <EditPageLoadState
        isLoading={contract.isLoading || version.isLoading}
        message={notFound ? t("versions.notFound") : loadErrorMessage(err, t)}
        backTo={Number.isFinite(id) ? contractPath(id) : contractsPath}
        backLabel={t("contracts.backToContract")}
      />
    );
  }
  const data = contract.data;
  const canEdit = data.canWrite && isContentEditable(stored.lifecycle);
  const editingHint = t(editingHintKey(hasHard, tooLarge, dirty));
  const latestId = data.latestVersion?.id ?? null;

  return (
    <Stack gap="md">
      <PageHeader
        title={`${data.name} ${stored.version}`}
        description={
          <Group gap="xs" component="span">
            <TypeBadge type={data.type} size="xs" />
            <LifecyclePill lifecycle={stored.lifecycle} size="xs" />
            {stored.docTitle && <span>{stored.docTitle}</span>}
          </Group>
        }
        backTo={{ to: contractPath(id), label: t("contracts.backToContract") }}
        toolbar={<VersionViewToggle view={view} onChange={setView} disabled={editing} />}
        actions={
          <VersionHeaderActions
            contract={data}
            version={stored}
            editing={editing}
            canEdit={canEdit}
            latestId={latestId}
            transitionPending={actions.transition.isPending}
            recheckPending={actions.recheck.isPending}
            onTransition={(to) => actions.transition.mutate(to)}
            onEdit={() => setEditing(true)}
            onDownload={() => void downloads.download({ contractId: id, versionId: vid, system: data.system.name, contract: data.name, version: stored.version, format: stored.format, content: stored.content })}
            onRecheck={() => actions.recheck.mutate()}
            onDelete={() => remove.requestDelete({ id: vid, version: stored.version })}
          onSynced={refresh}
          />
        }
      />
      {actions.error && (
        <Alert color="red" variant="light" withCloseButton onClose={actions.dismissError}>
          {actions.error}
        </Alert>
      )}
      {downloads.error != null && (
        <Alert color="red" variant="light" withCloseButton onClose={downloads.dismissError} title={t("versions.downloadFailed")}>
          {loadErrorMessage(downloads.error, t)}
        </Alert>
      )}
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: MAIN_SPAN[view] }}>
          <Stack gap="xs">
            {view === "reader" ? (
              <LazyContractReader contract={data} version={stored} highlight={highlight} />
            ) : (
              <LazyCodeEditor
                value={text}
                onChange={editing ? setDraft : undefined}
                readOnly={!editing}
                format={detectFormat(text)}
                diagnostics={diagnostics}
                jumpTo={jump}
                ariaLabel={t("versions.editorAria")}
              />
            )}
            <VersionMetaStrip version={stored} />
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: ASIDE_SPAN[view] }}>
          <Box className={classes.stickyAside}>
            <Paper withBorder p="md" radius="md">
              <FindingsPanel
                findings={findings}
                mode={editing ? "live" : "stored"}
                checked={!editing || check.checked}
                checkComplete={editing ? (check.report?.checkerAvailable ?? true) : stored.checkComplete}
                baselineVersion={check.report?.baselineVersion}
                jumpBy={JUMP_BY[view]}
                onJump={(f) => {
                  if (view === "reader") {
                    if (f.path) setHighlight({ path: f.path, nonce: Date.now() });
                  } else if (f.line != null) {
                    setJump({ line: f.line, column: f.column, nonce: Date.now() });
                  }
                }}
              />
            </Paper>
          </Box>
        </Grid.Col>
      </Grid>
      {editing && (
        <>
          {save.error && (
            <Alert color="red" variant="light" title={save.error.message}>
              {save.error.detail}
            </Alert>
          )}
          <Paper withBorder p="md" radius="md" className={classes.stickyActions}>
            <Group justify="space-between">
              <Text size="sm" c="dimmed">
                {editingHint}
              </Text>
              <Group gap="sm">
                <Button
                  variant="default"
                  onClick={() => {
                    setEditing(false);
                    setDraft(null);
                    save.clearError();
                  }}
                  disabled={save.submitting}
                >
                  {t("common.action.cancel")}
                </Button>
                <Button onClick={() => void save.submit()} loading={save.submitting} disabled={!dirty || hasHard || tooLarge}>
                  {t("common.action.save")}
                </Button>
              </Group>
            </Group>
          </Paper>
        </>
      )}
      <SaveAnywayModal findings={save.waiverFindings} onCancel={save.cancelWaiver} onConfirm={() => void save.saveAnyway()} saving={save.submitting} />
      <ConfirmDeleteModal
        confirm={remove}
        title={t("versions.deleteTitle")}
        errorTitle={t("versions.deleteFailed")}
        errorMessage={(err) =>
          saveErrorMessage(err, t, { conflict: "versions.deleteNotDraft", forbidden: "contracts.saveForbidden", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })
        }
        body={(v) => t("versions.deleteBody", { version: v.version, name: data.name })}
      />
    </Stack>
  );
}
