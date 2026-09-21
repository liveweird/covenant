import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { Alert, Box, Button, Grid, Group, Paper, Select, Stack, Text, TextInput } from "@mantine/core";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { getContract, type ContractType } from "../api/contracts";
import { ApiError } from "../api/http";
import { createVersion, getVersion, listAllVersions } from "../api/versions";
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
import { blankTemplate, detectFormat, MAX_DOCUMENT_BYTES, type SeededDocument, utf8Length } from "../utils/document";
import { toDiagnostics } from "../utils/findingDiagnostics";
import { loadErrorMessage } from "../utils/saveError";
import { bumpSemver, isValidSemver, MAX_VERSION_LENGTH, parseSemver, type BumpKind } from "../utils/semver";
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
  // The sync modal seeds a locked version's successor with the repo copy (see SyncVersionModal).
  const seeded = useLocation().state as SeededDocument | null;
  const id = Number(idParam);
  const fromParam = seeded ? null : params.get("from");
  const requestedMajor = startingMajor(seeded?.version, params.get("major"));
  const contract = useQuery({ queryKey: ["contracts", "detail", id], queryFn: () => getContract(id), enabled: Number.isFinite(id) });

  const [version, setVersion] = useState<string | null>(seeded?.version ?? null);
  const [content, setContent] = useState<string | null>(seeded?.content ?? null);
  // The text as fetched from its URL: the create carries the reference only while the text is
  // still that copy byte for byte — an edited fetch is no longer "the repo copy right now".
  const [fetched, setFetched] = useState<{ text: string; sourceUrl: string } | null>(
    seeded?.sourceUrl ? { text: seeded.content, sourceUrl: seeded.sourceUrl } : null,
  );
  const [copyFrom, setCopyFrom] = useState<string | null>(fromParam);
  const [jump, setJump] = useState<JumpRequest | null>(null);
  const source = useQuery({
    queryKey: ["contracts", "version", id, Number(copyFrom)],
    queryFn: () => getVersion(id, Number(copyFrom)),
    enabled: copyFrom != null && Number.isFinite(Number(copyFrom)),
  });
  const selectedMajor = requestedMajor ?? (source.data ? parseSemver(source.data.version)?.major : undefined);
  const versions = useQuery({
    queryKey: ["contracts", "versions", id, "all", selectedMajor ?? "all"],
    queryFn: () => listAllVersions(id, selectedMajor),
    enabled: Number.isFinite(id) && (copyFrom == null || selectedMajor != null),
  });
  const highest = versions.data?.[0]?.version ?? null;
  const bumpBase = source.data?.version ?? highest;

  // Defaults resolve once the selected line/source is in: the next patch in that line (or
  // 1.0.0), and the copied text (or type template). Explicit user edits win from then on.
  const effectiveVersion = version ?? (bumpBase ? (bumpSemver(bumpBase, "patch") ?? "") : initialVersion(selectedMajor));
  const type = contract.data?.type;
  const effectiveContent = initialContent(content, copyFrom, source.data?.content, type, effectiveVersion, contract.data?.name ?? "");
  const format = detectFormat(effectiveContent);
  // Naming the contract adds the breaking-change comparison against its highest published version
  // (an unparsable URL id is NaN, which the hook's JSON body carries as null — no branch needed).
  const checkDocument = { type: type ?? "OPENAPI", content: type ? effectiveContent : "", version: effectiveVersion || null, contractId: id };
  const check = useDocumentCheck(checkDocument);
  const diagnostics = toDiagnostics(check.findings, effectiveContent);

  const versionError = validateVersion(effectiveVersion, t("versions.validation.versionRequired"), t("versions.validation.versionFormat"));
  const contentError = validateContent(effectiveContent, t("versions.validation.contentRequired"), t("versions.validation.contentTooLarge"));
  const hasHard = check.findings.some((f) => f.source === "SYNTAX");

  const save = useVersionSave({
    document: () => checkDocument,
    saveRequest: (options) =>
      createVersion(
        id,
        { version: effectiveVersion.trim(), content: effectiveContent, sourceUrl: fetched && fetched.text === effectiveContent ? fetched.sourceUrl : null },
        options,
      ),
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
  const copyOptions = (versions.data ?? []).map((v) => ({ value: String(v.id), label: `${v.version} (${t(`versions.lifecycle.${v.lifecycle}`)})` }));

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
      <SourceLoadAlert error={source.isError ? source.error : null} />
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
                <Button key={kind} size="xs" variant="default" disabled={!bumpBase} onClick={() => bumpBase && setVersion(bumpSemver(bumpBase, kind) ?? "")}>
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
            onLoad={(text, origin) => {
              setContent(text);
              setCopyFrom(null);
              setFetched(origin ? { text, sourceUrl: origin } : null);
            }}
          />
        </Stack>
      </Paper>
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Stack gap="xs">
            <LazyCodeEditor value={effectiveContent} onChange={setContent} format={format} diagnostics={diagnostics} jumpTo={jump} ariaLabel={t("versions.editorAria")} />
            <ContentError message={contentError} />
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
                baselineVersion={check.report?.baselineVersion}
                onJump={(f) => f.line != null && setJump({ line: f.line, column: f.column, nonce: Date.now() })}
              />
            </Paper>
          </Box>
        </Grid.Col>
      </Grid>
      <SaveError error={save.error} />
      <Paper withBorder p="md" radius="md" className={`${classes.stickyActions} ${classes.stickyActionsPage}`}>
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {hasHard ? t("versions.blockedBySyntax") : check.checking ? t("findings.checking") : t("versions.readyHint")}
          </Text>
          <Group gap="sm">
            <Button variant="default" onClick={() => navigate(contractPath(id))} disabled={save.submitting}>
              {t("common.action.cancel")}
            </Button>
            <Button onClick={() => void save.submit()} loading={save.submitting} disabled={!!versionError || !!contentError || hasHard || source.isError}>
              {t("versions.saveDraft")}
            </Button>
          </Group>
        </Group>
      </Paper>
      <SaveAnywayModal findings={save.waiverFindings} onCancel={save.cancelWaiver} onConfirm={() => void save.saveAnyway()} saving={save.submitting} />
    </Stack>
  );
}

function parseMajor(raw: string | null): number | undefined {
  if (raw == null || !/^\d+$/.test(raw)) return undefined;
  const value = Number(raw);
  return Number.isSafeInteger(value) ? value : undefined;
}

function startingMajor(seededVersion: string | undefined, majorParam: string | null): number | undefined {
  return seededVersion ? parseSemver(seededVersion)?.major : parseMajor(majorParam);
}

function initialVersion(major: number | undefined): string {
  if (major == null) return "1.0.0";
  return major === 0 ? "0.1.0" : `${major}.0.0`;
}

function initialContent(
  edited: string | null,
  copyFrom: string | null,
  sourceContent: string | undefined,
  type: ContractType | undefined,
  version: string,
  contractName: string,
): string {
  if (edited != null) return edited;
  if (copyFrom != null) return sourceContent ?? "";
  return type ? blankTemplate(type, version, contractName) : "";
}

function validateVersion(value: string, requiredMessage: string, formatMessage: string): string | null {
  if (!value.trim()) return requiredMessage;
  return value.length > MAX_VERSION_LENGTH || !isValidSemver(value) ? formatMessage : null;
}

function validateContent(value: string, requiredMessage: string, tooLargeMessage: string): string | null {
  if (!value.trim()) return requiredMessage;
  return utf8Length(value) > MAX_DOCUMENT_BYTES ? tooLargeMessage : null;
}

function SourceLoadAlert({ error }: { error: unknown | null }) {
  const { t } = useTranslation();
  if (!error) return null;
  return (
    <Alert color="red" variant="light" title={t("versions.sourceVersionLoadFailed")}>
      {loadErrorMessage(error, t)}
    </Alert>
  );
}

function ContentError({ message }: { message: string | null }) {
  return message ? (
    <Text size="sm" c="red">
      {message}
    </Text>
  ) : null;
}

function SaveError({ error }: { error: { message: string; detail?: string } | null }) {
  return error ? (
    <Alert color="red" variant="light" title={error.message}>
      {error.detail}
    </Alert>
  ) : null;
}
