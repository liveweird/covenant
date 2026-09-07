import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate, useParams } from "react-router-dom";
import { Alert, Box, Button, Grid, Group, Paper, SegmentedControl, Stack, Tabs, Text, TextInput } from "@mantine/core";
import { useMutation, useQuery } from "@tanstack/react-query";
import { IconClipboard, IconFileUpload, IconPlugConnected, IconWand } from "@tabler/icons-react";
import { getContract, type ContractType } from "../api/contracts";
import { ApiError } from "../api/http";
import { inferDocument, type HttpExchangeSample, type MessageBatchSample, type RelationSample } from "../api/infer";
import { listVersions } from "../api/versions";
import EditPageLoadState from "../components/EditPageLoadState";
import FindingsPanel from "../components/FindingsPanel";
import HarUpload from "../components/HarUpload";
import InferObservePanel from "../components/InferObservePanel";
import InferPasteForm from "../components/InferPasteForm";
import InferSampleList, { type InferSample } from "../components/InferSampleList";
import LazyCodeEditor from "../components/LazyCodeEditor";
import PageHeader from "../components/PageHeader";
import { contractPath, contractsPath, importContractPath, newVersionPath } from "../utils/contractLinks";
import type { SeededDocument } from "../utils/document";
import { saveErrorMessage, loadErrorMessage } from "../utils/saveError";
import { bumpSemver } from "../utils/semver";
import classes from "../theme.module.css";

const CONTRACT_TYPES: readonly ContractType[] = ["OPENAPI", "ASYNCAPI", "ODCS"];

/**
 * `/contracts/infer` (a brand-new contract, type picked here) and `/contracts/:id/infer` (an
 * existing contract, type and system already known): samples in — pasted, uploaded as a HAR
 * file, or pulled live through an Environment — a DRAFT document out. The feature stores
 * nothing; "Open in editor" hands the draft to `NewVersion`/`ImportContract` through
 * `location.state` exactly like the sync modal's repo copy, where the ordinary create path
 * checks and saves it.
 */
export default function InferContract() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { id: idParam } = useParams();
  const id = idParam != null ? Number(idParam) : null;
  const existing = id != null && Number.isFinite(id);

  const contract = useQuery({
    queryKey: ["contracts", "detail", id],
    queryFn: () => getContract(id as number),
    enabled: existing,
  });
  const versions = useQuery({
    queryKey: ["contracts", "versions", id, "all"],
    queryFn: () => listVersions(id as number, { page: 1, pageSize: 100, sort: "-version" }),
    enabled: existing,
  });
  const highest = versions.data?.items[0]?.version ?? null;

  const [typeChoice, setTypeChoice] = useState<ContractType>("OPENAPI");
  const type = existing ? (contract.data?.type ?? "OPENAPI") : typeChoice;

  const [name, setName] = useState("");
  const [version, setVersion] = useState<string | null>(null);
  const defaultVersion = existing ? (highest ? (bumpSemver(highest, "patch") ?? "1.0.0") : "1.0.0") : "1.0.0";
  const effectiveVersion = version ?? defaultVersion;

  const [httpSamples, setHttpSamples] = useState<HttpExchangeSample[]>([]);
  const [messageSamples, setMessageSamples] = useState<MessageBatchSample[]>([]);
  const [relationSamples, setRelationSamples] = useState<RelationSample[]>([]);

  const effectiveName = existing ? (contract.data?.name ?? "") : name;
  const infer = useMutation({
    mutationFn: () =>
      inferDocument({
        type,
        name: effectiveName.trim() || null,
        version: effectiveVersion.trim() || null,
        http: type === "OPENAPI" ? httpSamples : [],
        messages: type === "ASYNCAPI" ? messageSamples : [],
        relations: type === "ODCS" ? relationSamples : [],
      }),
  });
  const draft = infer.data;

  // A stale draft/preview must never survive a type switch or an edit to the sample set it was
  // generated from — every path that changes either resets the mutation, so "Open in editor"
  // (disabled on `!draft`) reflects only a draft that matches the CURRENT type and samples.
  function changeType(next: ContractType) {
    setTypeChoice(next);
    setHttpSamples([]);
    setMessageSamples([]);
    setRelationSamples([]);
    infer.reset();
  }

  const samples: readonly InferSample[] = type === "OPENAPI" ? httpSamples : type === "ASYNCAPI" ? messageSamples : relationSamples;

  function addSample(sample: InferSample) {
    if (type === "OPENAPI") setHttpSamples((prev) => [...prev, sample as HttpExchangeSample]);
    else if (type === "ASYNCAPI") setMessageSamples((prev) => [...prev, sample as MessageBatchSample]);
    else setRelationSamples((prev) => [...prev, sample as RelationSample]);
    infer.reset();
  }
  function removeSample(index: number) {
    if (type === "OPENAPI") setHttpSamples((prev) => prev.filter((_, i) => i !== index));
    else if (type === "ASYNCAPI") setMessageSamples((prev) => prev.filter((_, i) => i !== index));
    else setRelationSamples((prev) => prev.filter((_, i) => i !== index));
    infer.reset();
  }

  function openInEditor() {
    if (!draft) return;
    if (existing && id != null) {
      const seeded: SeededDocument = { content: draft.content, sourceUrl: null };
      navigate(newVersionPath(id), { state: seeded });
    } else {
      const seeded: SeededDocument = { content: draft.content, sourceUrl: null, type };
      navigate(importContractPath, { state: seeded });
    }
  }

  if (existing && (contract.isLoading || contract.isError || !contract.data)) {
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

  return (
    <Stack gap="md">
      <PageHeader
        title={t("infer.title")}
        description={t("infer.intro")}
        backTo={{
          to: existing ? contractPath(id as number) : contractsPath,
          label: t(existing ? "contracts.backToContract" : "contracts.backToList"),
        }}
      />
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          {!existing && <SegmentedControl value={typeChoice} onChange={(v) => changeType(v as ContractType)} data={[...CONTRACT_TYPES]} />}
          <Group align="flex-end" gap="sm" wrap="wrap">
            <TextInput label={t("common.field.name")} value={effectiveName} onChange={(e) => setName(e.currentTarget.value)} disabled={existing} w={280} />
            <TextInput
              label={t("versions.field.version")}
              value={effectiveVersion}
              onChange={(e) => setVersion(e.currentTarget.value)}
              ff="monospace"
              w={180}
            />
          </Group>
        </Stack>
      </Paper>
      <Paper withBorder p="lg" radius="md">
        <Stack gap="md">
          <InferSampleList type={type} samples={samples} onRemove={removeSample} />
          <Tabs defaultValue="paste">
            <Tabs.List>
              <Tabs.Tab value="paste" leftSection={<IconClipboard size={16} />}>
                {t("infer.tab.paste")}
              </Tabs.Tab>
              {type === "OPENAPI" && (
                <Tabs.Tab value="har" leftSection={<IconFileUpload size={16} />}>
                  {t("infer.tab.har")}
                </Tabs.Tab>
              )}
              <Tabs.Tab value="observe" leftSection={<IconPlugConnected size={16} />}>
                {t("infer.tab.observe")}
              </Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="paste" pt="md">
              <InferPasteForm type={type} onAdd={addSample} />
            </Tabs.Panel>
            {type === "OPENAPI" && (
              <Tabs.Panel value="har" pt="md">
                <HarUpload
                  onAdd={(exchanges) => {
                    setHttpSamples((prev) => [...prev, ...exchanges]);
                    infer.reset();
                  }}
                />
              </Tabs.Panel>
            )}
            <Tabs.Panel value="observe" pt="md">
              <InferObservePanel type={type} systemId={existing ? (contract.data?.system.id ?? null) : null} onAdd={addSample} />
            </Tabs.Panel>
          </Tabs>
        </Stack>
      </Paper>
      <Grid gap="md">
        <Grid.Col span={{ base: 12, lg: 8 }}>
          <Stack gap="xs">
            <Group justify="space-between" align="center">
              <Text fw={600} size="sm">
                {t("infer.preview.title")}
              </Text>
              <Button leftSection={<IconWand size={16} />} onClick={() => infer.mutate()} loading={infer.isPending} disabled={samples.length === 0}>
                {t("infer.generate")}
              </Button>
            </Group>
            {infer.isError && (
              <Alert color="red" variant="light" title={t("infer.generateFailed")}>
                {saveErrorMessage(infer.error, t, { invalid: "infer.error.invalid", failedStatus: "common.error.actionFailedStatus", failed: "common.error.actionFailed" })}
              </Alert>
            )}
            <LazyCodeEditor
              value={draft?.content ?? ""}
              readOnly
              format={draft?.format ?? "yaml"}
              ariaLabel={t("infer.preview.editorAria")}
              placeholder={t("infer.preview.placeholder")}
            />
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, lg: 4 }}>
          <Box className={classes.stickyAside}>
            <Paper withBorder p="md" radius="md">
              <FindingsPanel findings={draft?.notes ?? []} mode="stored" />
            </Paper>
          </Box>
        </Grid.Col>
      </Grid>
      <Paper withBorder p="md" radius="md" className={`${classes.stickyActions} ${classes.stickyActionsPage}`}>
        <Group justify="flex-end">
          <Button onClick={openInEditor} disabled={!draft}>
            {t("infer.openInEditor")}
          </Button>
        </Group>
      </Paper>
    </Stack>
  );
}
