import { Alert, Box, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { useTranslation } from "react-i18next";
import type { ContractResponse } from "../api/contracts";
import { getVersionModel, type VersionResponse } from "../api/versions";
import { findPointerElement } from "../utils/readerPointer";
import { loadErrorMessage } from "../utils/saveError";
import LoadingBlock from "./LoadingBlock";
import ReaderAsyncApi from "./ReaderAsyncApi";
import ReaderOdcs from "./ReaderOdcs";
import ReaderOpenApi from "./ReaderOpenApi";

const HIGHLIGHT_MS = 2000;

/**
 * The reader: the version's render model (keyed by its content hash) dispatched to the family
 * renderer, with the load / error / unparseable / truncated states, and the findings deep-link —
 * `highlight` is a finding's JSON pointer; the element carrying it (or its nearest rendered
 * ancestor) is scrolled to, focused and ringed for a moment.
 */
export default function ContractReader({
  contract,
  version,
  highlight,
}: {
  contract: ContractResponse;
  version: VersionResponse;
  highlight?: { path: string; nonce: number } | null;
}) {
  const { t } = useTranslation();
  const root = useRef<HTMLDivElement>(null);
  const model = useQuery({
    queryKey: ["contracts", "version", contract.id, version.id, "model", version.contentSha256],
    queryFn: () => getVersionModel(contract.id, version.id),
  });
  const ready = model.data != null;
  useEffect(() => {
    if (!highlight || !ready || !root.current) return;
    const el = findPointerElement(root.current, highlight.path);
    if (!el) return;
    el.scrollIntoView?.({ block: "center" });
    el.setAttribute("data-highlight", "true");
    el.setAttribute("tabindex", "-1");
    el.focus?.({ preventScroll: true });
    const timer = setTimeout(() => el.removeAttribute("data-highlight"), HIGHLIGHT_MS);
    return () => clearTimeout(timer);
  }, [highlight, ready]);

  if (model.isLoading) return <LoadingBlock />;
  if (model.isError || !model.data) {
    return (
      <Alert color="red" variant="light" role="alert">
        {loadErrorMessage(model.error, t)}
      </Alert>
    );
  }
  const data = model.data;
  let body;
  if (data.error) {
    body = (
      <Alert color="orange" variant="light" title={t("reader.unparseableTitle")}>
        {t("reader.unparseableBody")} {data.error.message}
      </Alert>
    );
  } else if (data.openApi) body = <ReaderOpenApi model={data.openApi} specVersion={data.specVersion} />;
  else if (data.asyncApi) body = <ReaderAsyncApi model={data.asyncApi} specVersion={data.specVersion} />;
  else if (data.odcs) body = <ReaderOdcs model={data.odcs} specVersion={data.specVersion} />;
  else {
    body = (
      <Text size="sm" c="dimmed">
        {t("reader.unsupported")}
      </Text>
    );
  }
  return (
    <Box ref={root} role="region" aria-label={t("reader.regionAria")}>
      {data.truncated && (
        <Alert color="gray" variant="light" mb="md">
          {t("reader.truncated")}
        </Alert>
      )}
      {body}
    </Box>
  );
}
