import { Box, Divider, Paper, Stack } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { useQuery } from "@tanstack/react-query";
import type { ContractResponse } from "../api/contracts";
import { getVersionCompatibility, type Finding, type RenderModel, type VersionResponse } from "../api/versions";
import type { VersionView } from "../hooks/useVersionView";
import { readerToc } from "../utils/readerToc";
import CompatibilityCard from "./CompatibilityCard";
import FindingsPanel from "./FindingsPanel";
import ReaderToc from "./ReaderToc";
import classes from "../theme.module.css";

/**
 * The version page's single side panel: Contents (Reader view only, once the render model has
 * loaded and the document offers one) above Findings, with the compact compatibility line above
 * that (stored view only, hidden while editing — the draft is not the stored document the report
 * is about) — one sticky, collapsible column identical for all three contract families.
 * `VersionPage` renders this only while its `asideOpen` toggle is on.
 */
export default function VersionAsidePanel({
  view,
  model,
  contract,
  version,
  editing,
  findings,
  mode,
  checked,
  checkComplete,
  baselineVersion,
  jumpBy,
  onJump,
}: {
  view: VersionView;
  model: RenderModel | undefined;
  contract: ContractResponse;
  version: VersionResponse;
  editing: boolean;
  findings: readonly Finding[];
  mode: "live" | "stored";
  checked: boolean;
  checkComplete: boolean;
  baselineVersion?: string | null;
  jumpBy: "line" | "path";
  onJump: (finding: Finding) => void;
}) {
  const { t } = useTranslation();
  const toc = view === "reader" && model ? readerToc(model, t) : [];
  const compatibility = useQuery({
    queryKey: ["contracts", "version", contract.id, version.id, "compatibility", "baseline"],
    queryFn: () => getVersionCompatibility(contract.id, version.id),
    enabled: !editing,
  });
  return (
    <Box className={classes.stickyAside}>
      <Paper withBorder p="md" radius="md">
        <Stack gap="md">
          {toc.length > 0 && (
            // The divider travels with the TOC: below `lg` the panel stacks under the document and
            // shows Findings only, with no stray hairline above them.
            <Box visibleFrom="lg">
              <Stack gap="md">
                <ReaderToc entries={toc} />
                <Divider />
              </Stack>
            </Box>
          )}
          {!editing && compatibility.data && (
            <>
              <CompatibilityCard variant="compact" report={compatibility.data} contractId={contract.id} />
              <Divider />
            </>
          )}
          <FindingsPanel findings={findings} mode={mode} checked={checked} checkComplete={checkComplete} baselineVersion={baselineVersion} jumpBy={jumpBy} onJump={onJump} />
        </Stack>
      </Paper>
    </Box>
  );
}
