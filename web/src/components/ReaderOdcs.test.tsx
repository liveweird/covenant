import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import i18n from "../i18n";
import { MODEL_ODCS } from "../test/readerFixtures";
import { odcsToc } from "../utils/readerToc";
import ReaderOdcs from "./ReaderOdcs";

const t = i18n.t;
const FULL = MODEL_ODCS.odcs!;

/** The optional sections: present in the rich fixture (asserted through ContractReader.test), absent here when the model lacks them. */
describe("ReaderOdcs", () => {
  test("a contract without team, roles, SLA, support, price or custom properties renders none of those sections", () => {
    const bare = { ...FULL, team: [], roles: [], slaProperties: [], support: [], price: undefined, customProperties: [], authoritativeDefinitions: [], servers: [] };
    renderWithProviders(<ReaderOdcs model={bare} specVersion="v3.1.0" />);
    expect(screen.getByRole("heading", { level: 3, name: "customers" })).toBeInTheDocument();
    for (const title of ["Team", "Roles", "Service levels", "Support", "Price", "Custom properties", "Servers"]) {
      expect(screen.queryByRole("heading", { name: title })).not.toBeInTheDocument();
    }
    // The description card still renders, and the TOC (now the version page's side panel content) lists only what exists.
    expect(screen.getByRole("heading", { name: "Description" })).toBeInTheDocument();
    const labels = odcsToc(bare, t).map((e) => e.label);
    expect(labels).not.toContain("Team");
    expect(labels).toEqual(["Description", "Datasets"]);
  });

  test("a contract without datasets or a description says so and shows the raw status when it maps to no lifecycle", () => {
    const empty = { ...FULL, datasets: [], description: undefined, status: "experimental", tags: [] };
    renderWithProviders(<ReaderOdcs model={empty} specVersion="v3.1.0" />);
    expect(screen.getByText("This contract declares no datasets.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Description" })).not.toBeInTheDocument();
    expect(screen.getByText("experimental")).toBeInTheDocument();
    expect(odcsToc(empty, t).map((e) => e.label)).not.toContain("Description");
  });

  test("the table sections lay their rows out one cell per column", () => {
    renderWithProviders(<ReaderOdcs model={FULL} specVersion="v3.1.0" />);
    for (const title of ["Team", "Roles", "Service levels", "Support"]) {
      if (screen.queryByRole("heading", { name: title })) {
        const table = screen.getByRole("table", { name: title });
        expect(table.querySelectorAll("tbody tr").length).toBeGreaterThan(0);
        expect(table.querySelectorAll("tbody tr")[0].querySelectorAll("td").length).toBeGreaterThanOrEqual(4);
      }
    }
  });
});
