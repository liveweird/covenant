import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { Lifecycle } from "../api/contracts";
import { recheckVersion, transitionVersion } from "../api/versions";
import { saveErrorMessage } from "../utils/saveError";
import { showSuccessToast } from "../utils/toast";

const ACTION_KEYS = {
  forbidden: "contracts.saveForbidden",
  notFound: "versions.gone",
  conflict: "versions.transitionConflict",
  failedStatus: "common.error.actionFailedStatus",
  failed: "common.error.actionFailed",
} as const;

/**
 * The whole-version operations on the version page — a lifecycle transition and a re-run of
 * the checks — with one shared inline error slot (the page renders it) and the ["contracts"]
 * invalidation + success toast on the way out.
 */
export function useVersionActions(contractId: number, versionId: number) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [error, setError] = useState<string | null>(null);
  const done = (toastKey: "versions.toast.transitioned" | "versions.toast.rechecked") => async () => {
    await queryClient.invalidateQueries({ queryKey: ["contracts"] });
    showSuccessToast(t(toastKey));
  };
  const transition = useMutation({
    mutationFn: (to: Lifecycle) => transitionVersion(contractId, versionId, to),
    onMutate: () => setError(null),
    onSuccess: done("versions.toast.transitioned"),
    onError: (err) => setError(saveErrorMessage(err, t, ACTION_KEYS)),
  });
  const recheck = useMutation({
    mutationFn: () => recheckVersion(contractId, versionId),
    onMutate: () => setError(null),
    onSuccess: done("versions.toast.rechecked"),
    onError: (err) => setError(saveErrorMessage(err, t, ACTION_KEYS)),
  });
  return { error, dismissError: () => setError(null), transition, recheck };
}
