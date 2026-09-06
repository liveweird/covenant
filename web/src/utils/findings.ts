import type { FindingSource, Severity } from "../api/versions";

/** The severities in display/filter order — shared by FindingsPanel and the Errors report. */
export const SEVERITIES = ["ERROR", "WARN", "INFO"] as const satisfies readonly Severity[];

/**
 * The colour vocabulary: red = blocks the save (a HARD syntax finding, or a soft ERROR that
 * needs the Save-anyway waiver — both stop a strict save), orange = a warning that saves
 * through, gray = informational.
 */
export const SEVERITY_COLOR: Record<Severity, string> = { ERROR: "red", WARN: "orange", INFO: "gray" };

/** The finding sources a check report can actually STORE — SYNTAX (a HARD parse failure never
 *  reaches storage) and CONFORMANCE (the try-it feature's live-only voice) never appear here. */
export const STORED_SOURCES = ["SCHEMA", "SEMANTIC", "LINT", "BREAKING", "SYSTEM"] as const satisfies readonly FindingSource[];
