import { useDebouncedValue } from "@mantine/hooks";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { checkDocument, type CheckReport, type DocumentCheckBody, type Finding } from "../api/versions";

/**
 * The editor's live check (Toadie's): the current text, debounced, through
 * `POST /contracts/versions/check` — findings-so-far, never a 400 for document problems. One
 * query for two consumers (the FindingsPanel lists them, the editor underlines them), so a
 * keystroke costs one request, not two. A blank document is not checked.
 */
export function useDocumentCheck(document: DocumentCheckBody): {
  report: CheckReport | null;
  findings: Finding[];
  /** True once a check has answered for SOME document — the panel's all-clear line waits for it. */
  checked: boolean;
  checking: boolean;
} {
  const json = JSON.stringify(document);
  const [debounced] = useDebouncedValue(json, 500);
  const enabled = document.content.trim().length > 0;

  const { data, isFetching } = useQuery({
    // Under the "contracts" prefix so contract mutations refresh a live check; keyed on the
    // debounced document with gcTime 0 — superseded documents' entries are dropped as soon as
    // the key moves on, so typing never accumulates cache entries.
    queryKey: ["contracts", "check", debounced],
    queryFn: () => checkDocument(JSON.parse(debounced) as DocumentCheckBody),
    placeholderData: keepPreviousData,
    gcTime: 0,
    enabled,
  });

  return {
    report: enabled ? (data ?? null) : null,
    findings: enabled ? (data?.findings ?? []) : [],
    checked: !enabled || data != null,
    checking: enabled && (isFetching || json !== debounced),
  };
}
