import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import type { Lifecycle } from "../api/contracts";
import { isContentEditable } from "../utils/lifecycle";
import { readStoredJson, writeStoredJson } from "./useStoredState";

export type VersionView = "reader" | "source";
const VIEWS: readonly VersionView[] = ["reader", "source"];
const STORAGE_KEY = "version.view";
const isView = (v: unknown): v is VersionView => VIEWS.includes(v as VersionView);

/**
 * The version page's view: `?view=` in the URL (a deep link — never persisted) → the remembered
 * choice (`covenant.viewSettings.version.view`) → the lifecycle default (an editable version opens
 * on Source, a published one on Reader). The default is FROZEN once the lifecycle is known: an
 * activation in place must not flip the page under the reader. Editing forces Source.
 */
export function useVersionView(lifecycle: Lifecycle | undefined, editing: boolean) {
  const [params] = useSearchParams();
  const fromUrl = params.get("view");
  const [chosen, setChosen] = useState<VersionView | null>(() => {
    if (isView(fromUrl)) return fromUrl;
    const stored = readStoredJson(STORAGE_KEY);
    return isView(stored) ? stored : null;
  });
  // The lifecycle default, decided once the lifecycle is known and then kept (state adjusted during
  // render — React re-renders before committing, so the first painted view is already the right one).
  const [frozen, setFrozen] = useState<VersionView | null>(null);
  if (frozen == null && lifecycle != null) setFrozen(isContentEditable(lifecycle) ? "source" : "reader");
  const view: VersionView = editing ? "source" : (chosen ?? frozen ?? "source");
  function setView(next: VersionView) {
    setChosen(next);
    writeStoredJson(STORAGE_KEY, next);
  }
  return { view, setView };
}
