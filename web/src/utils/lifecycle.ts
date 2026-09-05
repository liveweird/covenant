import type { Lifecycle } from "../api/contracts";

/** The lifecycle in order — the pills' and filters' display order. */
export const LIFECYCLES = ["DRAFT", "PROPOSED", "ACTIVE", "DEPRECATED", "RETIRED"] as const satisfies readonly Lifecycle[];

/**
 * The transition matrix, mirrored from the server's Lifecycle.kt: strictly forward, with the
 * one step back (PROPOSED → DRAFT) for a rejected proposal. RETIRED is terminal.
 */
export const TRANSITIONS: Record<Lifecycle, readonly Lifecycle[]> = {
  DRAFT: ["PROPOSED"],
  PROPOSED: ["DRAFT", "ACTIVE"],
  ACTIVE: ["DEPRECATED"],
  DEPRECATED: ["RETIRED"],
  RETIRED: [],
};

/** The app-wide colour vocabulary: gray draft, yellow proposed, teal active, orange deprecated, gray retired. */
export const LIFECYCLE_COLOR: Record<Lifecycle, string> = {
  DRAFT: "gray",
  PROPOSED: "yellow",
  ACTIVE: "teal",
  DEPRECATED: "orange",
  RETIRED: "gray",
};

/** The document text is editable only before the version is published. */
export const isContentEditable = (lifecycle: Lifecycle): boolean => lifecycle === "DRAFT" || lifecycle === "PROPOSED";

/** Only a DRAFT may be deleted — anything further is part of the contract's history. */
export const isDeletable = (lifecycle: Lifecycle): boolean => lifecycle === "DRAFT";

/** Transitions that take a version out of circulation — worth a confirm. */
export const isIrreversibleTransition = (to: Lifecycle): boolean => to === "DEPRECATED" || to === "RETIRED";
