import { useEffect, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate } from "react-router-dom";
import { Alert, Badge, Box, Button, Grid, Group, Paper, Select, Stack, Text, TextInput } from "@mantine/core";
import { useForm } from "@mantine/form";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { IconFileImport, IconListCheck } from "@tabler/icons-react";
import { checkImportContracts, importContracts, type ImportItem, type ImportItemResult, type ImportStatus } from "../api/contracts";
import { listSystems } from "../api/systems";
import DocumentSourcePicker from "../components/DocumentSourcePicker";
import FindingsPanel from "../components/FindingsPanel";
import LazyCodeEditor, { type JumpRequest } from "../components/LazyCodeEditor";
import OwnerSelect from "../components/OwnerSelect";
import PageHeader from "../components/PageHeader";
import { useDocumentCheck } from "../hooks/useDocumentCheck";
import {
  CONTRACT_TYPE_LABEL,
  CONTRACT_TYPES,
  contractDescription,
  contractFormValidation,
  splitOwnerValue,
  systemOptions,
  type ContractFormValues,
} from "../utils/contractForm";
import { contractPath, contractsPath, versionPath } from "../utils/contractLinks";
import { detectFormat, MAX_DOCUMENT_BYTES, utf8Length } from "../utils/document";
import { toDiagnostics } from "../utils/findingDiagnostics";
import { saveErrorMessage } from "../utils/saveError";
import { isValidSemver, MAX_VERSION_LENGTH } from "../utils/semver";
import { showSuccessToast } from "../utils/toast";
import classes from "../theme.module.css";

const STATUS_COLOR: Record<ImportStatus, string> = {
  CREATED: "teal",
  VERSION_ADDED: "teal",
  // Stored, but carrying waived findings — a caution, not a failure.
  CREATED_WITH_FINDINGS: "orange",
  VERSION_ADDED_WITH_FINDINGS: "orange",
  // Nothing stored — as blocking as INVALID, so the same red.
  INVALID: "red",
  CONFLICT: "red",
  FORBIDDEN: "red",
  ERROR: "red",
};
const STORED: readonly ImportStatus[] = ["CREATED", "VERSION_ADDED", "CREATED_WITH_FINDINGS", "VERSION_ADDED_WITH_FINDINGS"];

type ImportFormValues = ContractFormValues & { version: string };

/**
 * `/contracts/import`: one document → one new version, of an existing contract (matched by
 * system + name) or a brand-new one (then the owner is required). The text comes from a
 * paste, a file or a fetched URL; the live check runs as it lands and prefills the name and
 * the version from what the document declares. Import ALWAYS waives soft findings (the point
 * is getting the document in) and reports the row's status; Check is the dry run.
 */
export default function ImportContract() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const systems = useQuery({ queryKey: ["systems", "all"], queryFn: () => listSystems({ page: 1, pageSize: 100, sort: "name" }) });
  const [content, setContent] = useState("");
  const [jump, setJump] = useState<JumpRequest | null>(null);
  const [busy, setBusy] = useState<"import" | "check" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<{ mode: "import" | "check"; row: ImportItemResult } | null>(null);
  const form = useForm<ImportFormValues>({
    initialValues: { systemId: null, type: "OPENAPI", name: "", description: "", owner: null, version: "" },
    validate: {
      ...contractFormValidation(t, { withOwner: false }),
      version: (v) => (v.trim() && v.length <= MAX_VERSION_LENGTH && isValidSemver(v.trim()) ? null : t("versions.validation.versionFormat")),
    },
  });
  const check = useDocumentCheck({ type: form.values.type, content, version: form.values.version.trim() || null });
  const diagnostics = useMemo(() => toDiagnostics(check.findings, content), [check.findings, content]);
  const hasHard = check.findings.some((f) => f.source === "SYNTAX");
  const tooLarge = utf8Length(content) > MAX_DOCUMENT_BYTES;

  // Prefill from what the document declares — only fields the user has not typed into.
  function onDocument(text: string) {
    setContent(text);
    setResult(null);
  }
  const report = check.report;
  const title = report?.title ?? null;
  const declared = report?.declaredVersion ?? null;
  useEffect(() => {
    if (title && !form.isTouched("name") && form.values.name === "") form.setFieldValue("name", title.slice(0, 100));
    if (declared && !form.isTouched("version") && form.values.version === "" && isValidSemver(declared)) form.setFieldValue("version", declared);
    // The form object is stable per render cycle; only the report's values drive the prefill.
    // eslint-disable-next-line react-hooks/exhaustive-deps -- prefill reacts to the report only
  }, [title, declared]);

  async function run(mode: "import" | "check") {
    if (form.validate().hasErrors || !content.trim() || hasHard || tooLarge) return;
    const values = form.values;
    const item: ImportItem = {
      systemId: Number(values.systemId),
      type: values.type,
      name: values.name.trim(),
      description: contractDescription(values.description),
      ...splitOwnerValue(values.owner ?? ""),
      version: values.version.trim(),
      content,
    };
    setBusy(mode);
    setError(null);
    try {
      const rows = mode === "import" ? await importContracts([item]) : await checkImportContracts([item]);
      const row = rows[0];
      setResult({ mode, row });
      if (mode === "import" && STORED.includes(row.status)) {
        await queryClient.invalidateQueries({ queryKey: ["contracts"] });
        showSuccessToast(t("contracts.toast.imported"));
      }
    } catch (err) {
      setError(saveErrorMessage(err, t, { forbidden: "contracts.saveForbidden", invalid: "contracts.importInvalid", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" }));
    } finally {
      setBusy(null);
    }
  }

  const row = result?.row;
  return (
    <Stack gap="md">
      <PageHeader title={t("contracts.importTitle")} description={t("contracts.importIntro")} backTo={{ to: contractsPath, label: t("contracts.backToList") }} />
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <Group align="flex-start" gap="md" wrap="wrap">
            <Select
              label={t("contracts.field.system")}
              data={systemOptions(systems.data?.items ?? [])}
              searchable
              allowDeselect={false}
              w={260}
              {...form.getInputProps("systemId")}
            />
            <Select
              label={t("contracts.field.type")}
              data={CONTRACT_TYPES.map((type) => ({ value: type, label: CONTRACT_TYPE_LABEL[type] }))}
              allowDeselect={false}
              w={160}
              {...form.getInputProps("type")}
            />
            <TextInput label={t("common.field.name")} description={t("contracts.importNameHint")} maxLength={100} w={280} {...form.getInputProps("name")} />
            <TextInput label={t("versions.field.version")} description={t("contracts.importVersionHint")} maxLength={MAX_VERSION_LENGTH} ff="monospace" w={180} {...form.getInputProps("version")} />
          </Group>
          <Box maw={480}>
            <OwnerSelect value={form.values.owner} onChange={(v) => form.setFieldValue("owner", v)} description={t("contracts.importOwnerHint")} />
          </Box>
          <DocumentSourcePicker onLoad={onDocument} disabled={busy !== null} />
        </Stack>
      </Paper>
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <LazyCodeEditor value={content} onChange={onDocument} format={detectFormat(content)} diagnostics={diagnostics} jumpTo={jump} ariaLabel={t("versions.editorAria")} placeholder={t("contracts.importPlaceholder")} />
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Box className={classes.stickyAside}>
            <Paper withBorder p="md" radius="md">
              <FindingsPanel
                findings={check.findings}
                mode="live"
                checked={check.checked}
                checkComplete={report?.checkerAvailable ?? true}
                onJump={(f) => f.line != null && setJump({ line: f.line, column: f.column, nonce: Date.now() })}
              />
            </Paper>
          </Box>
        </Grid.Col>
      </Grid>
      {error && (
        <Alert color="red" variant="light" title={t("contracts.importFailed")}>
          {error}
        </Alert>
      )}
      {row && result && (
        <Alert color={STATUS_COLOR[row.status]} variant="light" title={t(result.mode === "check" ? `contracts.importCheckStatus.${row.status}` : `contracts.importStatus.${row.status}`)}>
          <Stack gap={4}>
            <Group gap="xs">
              <Badge color={STATUS_COLOR[row.status]} size="sm">
                {row.version}
              </Badge>
              <Text size="sm" fw={500}>
                {row.name}
              </Text>
              {row.errors > 0 && (
                <Text size="sm" c="dimmed">
                  {t("findings.count.ERROR", { count: row.errors })}
                </Text>
              )}
              {row.warnings > 0 && (
                <Text size="sm" c="dimmed">
                  {t("findings.count.WARN", { count: row.warnings })}
                </Text>
              )}
            </Group>
            {row.message && (
              <Text size="sm" style={{ overflowWrap: "anywhere" }}>
                {row.message}
              </Text>
            )}
            {row.contractId != null && row.versionId != null && (
              <Button component={RouterLink} to={versionPath(row.contractId, row.versionId)} size="xs" variant="default" w="fit-content">
                {t("contracts.importOpenVersion")}
              </Button>
            )}
            {row.contractId != null && row.versionId == null && result.mode === "check" && (
              <Button component={RouterLink} to={contractPath(row.contractId)} size="xs" variant="default" w="fit-content">
                {t("contracts.importOpenContract")}
              </Button>
            )}
          </Stack>
        </Alert>
      )}
      <Paper withBorder p="md" radius="md" className={classes.stickyActions}>
        <Group justify="space-between">
          <Text size="sm" c="dimmed">
            {hasHard ? t("versions.blockedBySyntax") : tooLarge ? t("versions.validation.contentTooLarge") : check.checking ? t("findings.checking") : t("contracts.importHint")}
          </Text>
          <Group gap="sm">
            <Button variant="default" onClick={() => navigate(contractsPath)} disabled={busy !== null}>
              {t("common.action.cancel")}
            </Button>
            <Button variant="default" leftSection={<IconListCheck size={16} />} onClick={() => void run("check")} loading={busy === "check"} disabled={busy !== null || !content.trim() || hasHard || tooLarge}>
              {t("contracts.importCheck")}
            </Button>
            <Button leftSection={<IconFileImport size={16} />} onClick={() => void run("import")} loading={busy === "import"} disabled={busy !== null || !content.trim() || hasHard || tooLarge}>
              {t("contracts.importRun")}
            </Button>
          </Group>
        </Group>
      </Paper>
    </Stack>
  );
}
