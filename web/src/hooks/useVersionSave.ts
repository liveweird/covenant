import { useState } from "react";
import { useTranslation } from "react-i18next";
import { ApiError } from "../api/http";
import { softRejectionFindings, type DocumentCheckBody, type Finding, type SaveOptions, type VersionResponse } from "../api/versions";
import { saveErrorMessage, type SaveErrorKeys } from "../utils/saveError";

/**
 * The document save flow shared by the new-version and edit-content screens (Toadie's
 * `useCatalogFileSave`): a STRICT save → on a 400 naming blocking findings, park the request
 * for the Save-anyway modal (confirming retries with the `allowInvalid` waiver) → on any other
 * failure render the page's fixed vocabulary, with the server's RFC 7807 detail as the second
 * line (the SemVer rules and HARD rejections are specific, and the detail says which).
 */
export function useVersionSave({
  document,
  saveRequest,
  onSaved,
  errorKeys,
}: {
  /** The document as the live check sees it — re-checked to list a soft rejection's findings. */
  document: () => DocumentCheckBody;
  saveRequest: (options?: SaveOptions) => Promise<VersionResponse>;
  onSaved: (saved: VersionResponse, waived: boolean) => Promise<void> | void;
  errorKeys: SaveErrorKeys;
}) {
  const { t } = useTranslation();
  const [error, setError] = useState<{ message: string; detail?: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [waiverFindings, setWaiverFindings] = useState<Finding[] | null>(null);

  function fail(err: unknown) {
    setError({ message: saveErrorMessage(err, t, errorKeys), detail: err instanceof ApiError ? err.detail : undefined });
  }

  async function submit() {
    setError(null);
    setSubmitting(true);
    try {
      const saved = await saveRequest();
      await onSaved(saved, false);
    } catch (err) {
      const findings = await softRejectionFindings(err, document()).catch(() => null);
      if (findings) setWaiverFindings(findings);
      else fail(err);
    } finally {
      setSubmitting(false);
    }
  }

  async function saveAnyway() {
    if (!waiverFindings) return;
    setSubmitting(true);
    try {
      const saved = await saveRequest({ allowInvalid: true });
      await onSaved(saved, true);
    } catch (err) {
      fail(err);
    } finally {
      setWaiverFindings(null);
      setSubmitting(false);
    }
  }

  return {
    error,
    submitting,
    waiverFindings,
    cancelWaiver: () => setWaiverFindings(null),
    clearError: () => setError(null),
    submit,
    saveAnyway,
  };
}
