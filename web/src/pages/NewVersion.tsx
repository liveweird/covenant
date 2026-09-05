import { useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, Box, Button, Grid, Group, Paper, Select, Stack, Text, TextInput } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getContract } from "../api/contracts";
import { ApiError } from "../api/http";
import { createVersion, getVersion, listVersions } from "../api/versions";
import DocumentSourcePicker from "../components/DocumentSourcePicker";
import EditPageLoadState from "../components/EditPageLoadState";
import FindingsPanel from "../components/FindingsPanel";
import LazyCodeEditor, { type JumpRequest } from "../components/LazyCodeEditor";
import PageHeader from "../components/PageHeader";
import SaveAnywayModal from "../components/SaveAnywayModal";
import TypeBadge from "../components/TypeBadge";
import { useDocumentCheck } from "../hooks/useDocumentCheck";
import { useVersionSave } from "../hooks/useVersionSave";
import { contractPath, contractsPath, versionPath } from "../utils/contractLinks";
import { blankTemplate, detectFormat, MAX_DOCUMENT_BYTES, utf8Length } from "../utils/document";
import { toDiagnostics } from "../utils/findingDiagnostics";
import { loadErrorMessage } from "../utils/saveError";
import { bumpSemver, compareSemver, isValidSemver, MAX_VERSION_LENGTH, parseSemver, type BumpKind } from "../utils/semver";
import { showSuccessToast } from "../utils/toast";
import classes from "../theme.module.css";

const BUMPS = ["major", "minor", "patch"] as const satisfies readonly BumpKind[];

/**
 * `/contracts/:id/versions/new?from=`: a new DRAFT. The number is typed or bumped off the
 * highest existing version (Major/Minor/Patch); the text starts blank (a per-type template),
 * copied from a stored version (`from`, or the picker), a file, or a fetched URL; the live check
 * runs beside the editor as the user types. The save is strict — soft errors go through
 * Save-anyway, a HARD (syntax) rejection never does.
 */
export default function NewVersion() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { id: idParam } = useParams();
  const [params] = useSearchParams();
  const id = Number(idParam);
  const fromParam = params.get("from");
  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });
  const versions = useQuery({
    queryKey: ["contracts", "versions", id, "all"],
    queryFn: () => listVersions(id, { page: 1, pageSize: 100, sort: "-version" }),
    enabled: Number.isFinite(id),
  });
  const highest = versions.data?.items[0]?.version ?? null;

  const [version, setVersion] = useState<string | null>(null);
  const [content, setContent] = useState<string | null>(null);
  const [copyFrom, setCopyFrom] = useState<string | null>(fromParam);
  const [jump, setJump] = useState<JumpRequest | null>(null);
  const source = useQuery({
    queryKey: ["contracts", "version", id, Number(copyFrom)],
    queryFn: () => getVersion(id, Number(copyFrom)),
    enabled: copyFrom != null && Number.isFinite(Number(copyFrom)),
  });

  // Defaults resolve once the data is in: the next patch after the highest (or 1.0.0), and
  // the copied text (or the type's blank template). A user edit wins from then on.
  const effectiveVersion = version ?? (highest ? (bumpSemver(highest, "patch") ?? "") : "1.0.0");
  const type = contract.data?.type;
  const effectiveContent =
    content ?? (copyFrom != null ? (source.data?.content ?? "") : type ? blankTemplate(type, effectiveVersion, contract.data?.name ?? "") : "");
  const format = detectFormat(effectiveContent);
  const check = useDocumentCheck({ type: type ?? "OPENAPI", content: type ? effectiveContent : "", version: effectiveVersion || null });
  const diagnostics = useMemo(() => toDiagnostics(check.findings, effectiveContent), [check.findings, effectiveContent]);

  const versionError = (() => {
    if (!effectiveVersion.trim()) return t("versions.validation.versionRequired");
    if (effectiveVersion.length > MAX_VERSION_LENGTH || !isValidSemver(effectiveVersion)) return t("versions.validation.versionFormat");
    if (highest) {
      const a = parseSemver(effectiveVersion);
      const b = parseSemver(highest);
      if (a && b && compareSemver(a, b) <= 0) return t("versions.validation.versionNotAbove", { highest });
    }
    return null;
  })();
  const contentError = !effectiveContent.trim()
    ? t("versions.validation.contentRequired")
    : utf8Length(effectiveContent) > MAX_DOCUMENT_BYTES
      ? t("versions.validation.contentTooLarge")
      : null;
  const hasHard = check.findings.some((f) => f.source === "SYNTAX");

  const save = useVersionSave({
    document: () => ({ type: type ?? "OPENAPI", content: effectiveContent, version: effectiveVersion }),
    saveRequest: (options) => createVersion(id, { version: effectiveVersion.trim(), content: effectiveContent }, options),
    onSaved: async (saved, waived) => {
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      showSuccessToast(t(waived ? "versions.toast.createdWithFindings" : "versions.toast.created"));
      navigate(versionPath(id, saved.id), { replace: true });
    },
    errorKeys: { forbidden: "contracts.saveForbidden", notFound: "contracts.saveGone", conflict: "versions.saveConflict", invalid: "versions.saveInvalid", failedStatus: "common.error.saveFailedStatus", failed: "common.error.saveFailedNetwork" },
  });

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
  const data = contract.data;
  if (!data.canWrite) {
    return <EditPageLoadState isLoading={false} message={t("contracts.saveForbidden")} backTo={contractPath(id)} backLabel={t("contracts.backToContract")} />;
  }
  const copyOptions = (versions.data?.items ?? []).map((v) => ({ value: String(v.id), label: `${v.version} (${t(`versions.lifecycle.${v.lifecycle}`)})` }));

  return (
    <Stack gap="md">
      <PageHeader
        title={t("versions.newTitle", { name: data.name })}
        description={
          <Group gap="xs" component="span">
            <TypeBadge type={data.type} size="xs" />
            <span>{highest ? t("versions.newIntroHighest", { highest }) : t("versions.newIntroFirst")}</span>
          </Group>
        }
        backTo={{ to: contractPath(id), label: t("contracts.backToContract") }}
      />
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <Group align="flex-end" gap="sm" wrap="wrap">
            <TextInput
              label={t("versions.field.version")}
              description={t("versions.field.versionHint")}
              value={effectiveVersion}
              onChange={(e) => setVersion(e.currentTarget.value)}
              error={versionError}
              maxLength={MAX_VERSION_LENGTH}
              ff="monospace"
              w={220}
              data-autofocus
            />
            <Group gap={4} pb={versionError ? 26 : 6}>
              {BUMPS.map((kind) => (
                <Button key={kind} size="xs" variant="default" disabled={!highest} onClick={() => highest && setVersion(bumpSemver(highest, kind) ?? "")}>
                  {t(`versions.bump.${kind}`)}
                </Button>
              ))}
            </Group>
            <Select
              label={t("versions.field.startFrom")}
              description={t("versions.field.startFromHint")}
              data={copyOptions}
              value={copyFrom}
              onChange={(v) => {
                setCopyFrom(v);
                setContent(null);
              }}
              placeholder={t("versions.blankTemplate")}
              clearable
              clearButtonProps={{ "aria-label": t("versions.clearStartFrom") }}
              w={260}
            />
          </Group>
          <DocumentSourcePicker
            onLoad={(text) => {
              setContent(text);
              setCopyFrom(null);
            }}
          />
        </Stack>
      </Paper>
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Stack gap="xs">
            <LazyCodeEditor value={effectiveContent} onChange={setContent} format={format} diagnostics={diagnostics} jumpTo={jump} ariaLabel={t("versions.editorAria")} />
            {contentError && (
              <Text size="sm" c="red">
                {contentError}
              </Text>
            )}
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Box className={classes.stickyAside}>
            <Paper withBorder p="md" radius="md">
              <FindingsPanel
                findings={check.findings}
                mode="live"
                checked={check.checked}
                checkComplete={check.report?.checkerAvailable ?? true}
                onJump={(f) => f.line != null && setJump({ line: f.line, column: f.column, nonce: Date.now() })}
              />
            </Paper>
          </Box>
        </Grid.Col>
      </Grid>
      {save.error && (
        <Alert color="red" variant="light" title={save.error.message}>
          {save.error.detail}
        </Alert>
      )}
      <Paper withBorder p="md" radius="md" className={classes.stickyActions}>
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {hasHard ? t("versions.blockedBySyntax") : check.checking ? t("findings.checking") : t("versions.readyHint")}
          </Text>
          <Group gap="sm">
            <Button variant="default" onClick={() => navigate(contractPath(id))} disabled={save.submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button onClick={() => void save.submit()} loading={save.submitting} disabled={!!versionError || !!contentError || hasHard}>
              {t("versions.saveDraft")}
            </Button>
          </Group>
        </Group>
      </Paper>
      <SaveAnywayModal findings={save.waiverFindings} onCancel={save.cancelWaiver} onConfirm={() => void save.saveAnyway()} saving={save.submitting} />
    </Stack>
  );
}
