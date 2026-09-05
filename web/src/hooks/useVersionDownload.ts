import { useState } from "react";
import { getVersionContent, type DocumentFormat } from "../api/versions";
import { documentFileName, downloadText } from "../utils/document";

export type DownloadTarget = {
  contractId: number;
  versionId: number;
  system: string;
  contract: string;
  version: string;
  format: DocumentFormat;
  /** The text when the page already holds it (the viewer) — skips the fetch. */
  content?: string;
};

const MIME: Record<DocumentFormat, string> = { yaml: "application/yaml", json: "application/json" };

/**
 * One version's raw document as a file save (the versions table's rows and the viewer's
 * Download button). Owns the caught failure (for a status-aware message) and the in-flight id
 * (a second click while one runs is a no-op); the page renders the dismissible error.
 */
export function useVersionDownload() {
  const [error, setError] = useState<unknown>(null);
  const [downloadingId, setDownloadingId] = useState<number | null>(null);

  async function download(target: DownloadTarget) {
    if (downloadingId !== null) return;
    setError(null);
    setDownloadingId(target.versionId);
    try {
      const text = target.content ?? (await getVersionContent(target.contractId, target.versionId));
      downloadText(documentFileName(target), text, MIME[target.format]);
    } catch (err) {
      setError(err);
    } finally {
      setDownloadingId(null);
    }
  }

  return { error, downloadingId, dismissError: () => setError(null), download };
}
