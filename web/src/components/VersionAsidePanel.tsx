import { Box, Divider, Paper, Stack } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { Finding, RenderModel } from "../api/versions";
import type { VersionView } from "../hooks/useVersionView";
import { readerToc } from "../utils/readerToc";
import FindingsPanel from "./FindingsPanel";
import ReaderToc from "./ReaderToc";
import classes from "../theme.module.css";

/**
 * The version page's single side panel: Contents (Reader view only, once the render model has
 * loaded and the document offers one) above Findings — one sticky, collapsible column identical
 * for all three contract families. `VersionPage` renders this only while its `asideOpen` toggle is on.
 */
export default function VersionAsidePanel({
  view,
  model,
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
          <FindingsPanel findings={findings} mode={mode} checked={checked} checkComplete={checkComplete} baselineVersion={baselineVersion} jumpBy={jumpBy} onJump={onJump} />
        </Stack>
      </Paper>
    </Box>
  );
}
