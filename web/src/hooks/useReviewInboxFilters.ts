import { useDebouncedValue } from "@mantine/hooks";
import { useSearchParams } from "react-router-dom";
import type { ReviewInboxAttention, ReviewInboxScope } from "../api/reviewInbox";

const SCOPES = ["RELATED", "OWNED", "FOLLOWED", "ALL"] as const satisfies readonly ReviewInboxScope[];
const ATTENTIONS = ["AWAITING_MY_REVIEW", "CHANGES_REQUESTED", "NEEDS_NEW_REVIEW"] as const satisfies readonly ReviewInboxAttention[];

export function useReviewInboxFilters() {
  const [params, setParams] = useSearchParams();
  const q = params.get("q") ?? "";
  const scopeParam = params.get("scope");
  const scope = SCOPES.includes(scopeParam as ReviewInboxScope) ? scopeParam as ReviewInboxScope : "RELATED";
  const attentionParam = params.get("attention");
  const attention = ATTENTIONS.includes(attentionParam as ReviewInboxAttention) ? attentionParam as ReviewInboxAttention : null;
  const [debouncedQ] = useDebouncedValue(q.trim(), 300);
  function setParam(name: "q" | "scope" | "attention", value: string | null) {
    const next = new URLSearchParams(params);
    if (value == null || value === "" || (name === "scope" && value === "RELATED")) next.delete(name);
    else next.set(name, value);
    setParams(next, { replace: true });
  }
  const values = { q: debouncedQ || undefined, scope, attention: attention ?? undefined };
  return {
    values,
    deps: [debouncedQ, scope, attention],
    activeCount: (debouncedQ ? 1 : 0) + (scope === "RELATED" ? 0 : 1) + (attention ? 1 : 0),
    slots: {
      q, setQ: (value: string) => setParam("q", value),
      scope, setScope: (value: ReviewInboxScope) => setParam("scope", value),
      attention, setAttention: (value: ReviewInboxAttention | null) => setParam("attention", value),
    },
  };
}

export type ReviewInboxFilterState = ReturnType<typeof useReviewInboxFilters>;
