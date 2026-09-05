import { describe, expect, test } from "vitest";
import { renderWithProviders, screen } from "../test/render";
import CheckSummaryBadges from "./CheckSummaryBadges";
import LifecyclePill from "./LifecyclePill";
import OwnerChip from "./OwnerChip";
import TypeBadge from "./TypeBadge";
import { OWNER_TEAM } from "../test/contractsFixtures";

describe("the small badges", () => {
  test("TypeBadge names the standard; LifecyclePill the state", () => {
    renderWithProviders(
      <>
        <TypeBadge type="ASYNCAPI" />
        <LifecyclePill lifecycle="RETIRED" />
      </>,
    );
    expect(screen.getByText("AsyncAPI")).toBeInTheDocument();
    expect(screen.getByText("Retired")).toBeInTheDocument();
  });

  test("OwnerChip links a live team, flags a deleted owner, and renders a person as text", () => {
    const { rerender } = renderWithProviders(<OwnerChip owner={OWNER_TEAM} />);
    expect(screen.getByRole("link", { name: "Payments Team" })).toHaveAttribute("href", "/teams/3");
    rerender(<OwnerChip owner={{ ...OWNER_TEAM, deleted: true }} />);
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(screen.getByText("deleted")).toBeInTheDocument();
    rerender(<OwnerChip owner={{ kind: "USER", id: 2, name: "Reg User", deleted: false }} />);
    expect(screen.getByText("Reg User")).toBeInTheDocument();
    expect(screen.getByLabelText("Person")).toBeInTheDocument();
  });

  test("CheckSummaryBadges: counts when present, clean otherwise, and the incomplete flag", () => {
    const { rerender } = renderWithProviders(<CheckSummaryBadges errors={2} warnings={1} complete />);
    expect(screen.getByText("2 errors")).toBeInTheDocument();
    expect(screen.getByText("1 warnings")).toBeInTheDocument();
    rerender(<CheckSummaryBadges errors={0} warnings={0} complete={false} />);
    expect(screen.getByText("clean")).toBeInTheDocument();
    expect(screen.getByLabelText("Checks incomplete")).toBeInTheDocument();
  });
});
