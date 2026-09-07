import { describe, expect, test } from "vitest";
import { renderWithProviders, screen, within } from "../test/render";
import CompatibilityCard from "./CompatibilityCard";
import type { CompatibilityReport } from "../api/versions";
import { FINDING } from "../test/contractsFixtures";

const FROM = { id: 10, version: "1.0.0", lifecycle: "ACTIVE" as const };
const TO = { id: 11, version: "1.1.0", lifecycle: "DRAFT" as const };
const NOTE = { severity: "INFO" as const, source: "SYSTEM" as const, code: "CHECKER_UNAVAILABLE", message: "the checker sidecar could not be reached" };

function report(overrides: Partial<CompatibilityReport>): CompatibilityReport {
  return {
    from: FROM,
    to: TO,
    verdict: "FULL",
    bump: "PATCH",
    backward: { compatible: true, findings: [] },
    forward: { compatible: true, findings: [] },
    checkerAvailable: true,
    ...overrides,
  };
}

describe("CompatibilityCard — full variant", () => {
  test("FULL verdict with a holding patch bump: badge, sentence and two 'Nothing' sections", () => {
    renderWithProviders(<CompatibilityCard variant="full" report={report({ verdict: "FULL", bump: "PATCH" })} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Fully compatible")).toBeInTheDocument();
    expect(within(card).getByText(/is fully compatible with/)).toBeInTheDocument();
    expect(within(card).getByText(/patch bump over 1\.0\.0.*and it holds/)).toBeInTheDocument();
    expect(within(card).getAllByText("Nothing.")).toHaveLength(2);
    expect(within(card).getByText("What breaks consumers of 1.0.0")).toBeInTheDocument();
    expect(within(card).getByText("What breaks consumers of 1.1.0")).toBeInTheDocument();
  });

  test("BACKWARD verdict: the minor bump's promise holds while the forward section lists what breaks, pluralized", () => {
    const r = report({
      verdict: "BACKWARD",
      bump: "MINOR",
      backward: { compatible: true, findings: [] },
      forward: { compatible: false, findings: [FINDING, FINDING] },
    });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Backward compatible")).toBeInTheDocument();
    expect(within(card).getByText(/minor bump over 1\.0\.0.*and it holds/)).toBeInTheDocument();
    expect(within(card).getAllByRole("listitem")).toHaveLength(2);
    expect(within(card).getByText("Nothing.")).toBeInTheDocument();
  });

  test("FORWARD verdict with a major bump: the backward section lists one breaking finding", () => {
    const r = report({
      verdict: "FORWARD",
      bump: "MAJOR",
      backward: { compatible: false, findings: [FINDING] },
      forward: { compatible: true, findings: [] },
    });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Forward compatible")).toBeInTheDocument();
    expect(within(card).getByText(/major bump over 1\.0\.0, which permits breaking changes/)).toBeInTheDocument();
    expect(within(card).getByRole("list", { name: "Findings" })).toBeInTheDocument();
  });

  test("NONE verdict with a downgrade bump", () => {
    const r = report({
      verdict: "NONE",
      bump: "DOWNGRADE",
      backward: { compatible: false, findings: [FINDING] },
      forward: { compatible: false, findings: [FINDING] },
    });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Not compatible")).toBeInTheDocument();
    expect(within(card).getByText(/is not compatible with 1\.0\.0 in either direction/)).toBeInTheDocument();
    expect(within(card).getByText(/1\.1\.0 is older than 1\.0\.0 — the directions read the other way round/)).toBeInTheDocument();
  });

  test("a minor bump whose backward outcome could not be verified names that", () => {
    const r = report({
      verdict: "UNKNOWN",
      bump: "MINOR",
      backward: { compatible: null, findings: [NOTE] },
      forward: { compatible: true, findings: [] },
    });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("Unknown")).toBeInTheDocument();
    expect(within(card).getByText(/could not be fully determined/)).toBeInTheDocument();
    expect(within(card).getByText(/whether it holds could not be verified/)).toBeInTheDocument();
    expect(within(card).getByText(NOTE.message)).toBeInTheDocument();
  });

  test("no ACTIVE predecessor renders the noBaseline alert instead of the sentences", () => {
    const r = report({ from: null, verdict: "UNKNOWN", bump: undefined });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    const card = screen.getByRole("region", { name: "Compatibility" });
    expect(within(card).getByText("No active version to compare with")).toBeInTheDocument();
    expect(within(card).queryByText("What breaks consumers of 1.0.0")).not.toBeInTheDocument();
  });

  test.each([
    ["MAJOR" as const, /major bump over 1\.0\.0, which permits breaking changes/],
    ["PRERELEASE" as const, /is a prerelease of 1\.0\.0 — compatibility is not guaranteed/],
    ["NONE" as const, /1\.1\.0 is the same version as 1\.0\.0/],
    ["DOWNGRADE" as const, /is older than 1\.0\.0 — the directions read the other way round/],
  ])("the %s bump renders its fixed sentence", (bump, expected) => {
    renderWithProviders(<CompatibilityCard variant="full" report={report({ bump })} />);
    expect(screen.getByRole("region", { name: "Compatibility" }).textContent).toMatch(expected);
  });

  test("a single breaking change uses the singular plural form", () => {
    const r = report({
      verdict: "FORWARD",
      bump: "PATCH",
      backward: { compatible: false, findings: [FINDING] },
      forward: { compatible: true, findings: [] },
    });
    renderWithProviders(<CompatibilityCard variant="full" report={r} />);
    expect(screen.getByText(/but 1 change breaks consumers of 1\.0\.0/)).toBeInTheDocument();
  });
});

describe("CompatibilityCard — compact variant", () => {
  test("names the active baseline, the verdict badge and links to the diff", () => {
    renderWithProviders(<CompatibilityCard variant="compact" report={report({ verdict: "BACKWARD" })} contractId={5} />);
    expect(screen.getByText("Compatibility with 1.0.0 (active):")).toBeInTheDocument();
    expect(screen.getByText("Backward compatible")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Details" })).toHaveAttribute("href", "/contracts/5/diff?from=10&to=11");
  });

  test("no ACTIVE predecessor shows a dimmed line and no link", () => {
    renderWithProviders(<CompatibilityCard variant="compact" report={report({ from: null, verdict: "UNKNOWN", bump: undefined })} contractId={5} />);
    expect(screen.getByText("No active version to compare with")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Details" })).not.toBeInTheDocument();
  });
});
