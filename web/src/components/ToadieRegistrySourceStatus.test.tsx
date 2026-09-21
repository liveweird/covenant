import { describe, expect, test, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ToadieRegistrySourceStatus from "./ToadieRegistrySourceStatus";
import { renderWithProviders } from "../test/render";

const SOURCE = {
  connectionId: 3,
  connectionName: "Architecture",
  entityId: "domain-7",
  identifier: "payments",
  title: "Payments in Toadie",
  url: "https://toadie.example/domains/payments",
  status: "AVAILABLE" as const,
  lastSyncedAt: 10,
  lastErrorCode: null,
  descriptionSynced: true,
  fallbackDomainId: null,
  cache: { state: "CURRENT" as const, lastAttemptAt: 10, lastSuccessAt: 10, refreshing: false, lastErrorCode: null },
};

describe("Toadie registry source status", () => {
  test("readers get the safe source link and current status without detach controls", () => {
    renderWithProviders(<ToadieRegistrySourceStatus source={SOURCE} />);
    expect(screen.getByRole("link", { name: "Open Payments in Toadie in Toadie" })).toHaveAttribute("href", SOURCE.url);
    expect(screen.getByText("Synchronized")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Detach Toadie source" })).not.toBeInTheDocument();
  });

  test("a missing source remains explicit even when its retained cache is stale", () => {
    renderWithProviders(<ToadieRegistrySourceStatus source={{ ...SOURCE, status: "MISSING", lastErrorCode: "NAME_CONFLICT", cache: { ...SOURCE.cache, state: "STALE", lastErrorCode: "GRAPHQL_ERROR" } }} onDetach={vi.fn()} />);
    expect(screen.getByText("Missing in Toadie")).toBeInTheDocument();
    expect(screen.getByText("Source cache is stale")).toBeInTheDocument();
    expect(screen.getByText("The source name conflicts with an existing record")).toBeInTheDocument();
    expect(screen.getByText("Last source refresh failed (GRAPHQL_ERROR)")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Detach Toadie source" })).toBeInTheDocument();
  });

  test("shows conflict availability and disabled freshness together", () => {
    renderWithProviders(<ToadieRegistrySourceStatus source={{ ...SOURCE, status: "CONFLICT", cache: { ...SOURCE.cache, state: "DISABLED", refreshing: true } }} />);
    expect(screen.getByText("Source conflict")).toBeInTheDocument();
    expect(screen.getByText("Automatic refresh is disabled")).toBeInTheDocument();
  });

  test("detaches only after confirmation and reports a failed detach inline", async () => {
    const detach = vi.fn().mockRejectedValueOnce(new Error("offline")).mockResolvedValueOnce(undefined);
    const user = userEvent.setup();
    renderWithProviders(<ToadieRegistrySourceStatus source={SOURCE} onDetach={detach} />);
    await user.click(screen.getByRole("button", { name: "Detach Toadie source" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach Toadie source" }));
    expect(await screen.findByText("Could not detach the Toadie source")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Detach Toadie source" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach Toadie source" }));
    await waitFor(() => expect(detach).toHaveBeenCalledTimes(2));
  });

  test("clears the loading state after a successful detach and later relink", async () => {
    const detach = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    const view = renderWithProviders(<ToadieRegistrySourceStatus source={SOURCE} onDetach={detach} />);
    await user.click(screen.getByRole("button", { name: "Detach Toadie source" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach Toadie source" }));
    await waitFor(() => expect(detach).toHaveBeenCalledTimes(1));
    view.rerender(<ToadieRegistrySourceStatus source={null} onDetach={detach} />);
    view.rerender(<ToadieRegistrySourceStatus source={SOURCE} onDetach={detach} />);
    await user.click(screen.getByRole("button", { name: "Detach Toadie source" }));
    await user.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Detach Toadie source" }));
    await waitFor(() => expect(detach).toHaveBeenCalledTimes(2));
  });

  test.each([
    ["CONFLICT", "CURRENT", "Source conflict"],
    ["DISCONNECTED", "CURRENT", "Disconnected"],
    ["AVAILABLE", "STALE", "Source cache is stale"],
    ["AVAILABLE", "DISABLED", "Automatic refresh is disabled"],
    ["AVAILABLE", "NEVER_SYNCED", "Awaiting first refresh"],
  ] as const)("renders %s / %s as an explicit source state", (status, state, label) => {
    renderWithProviders(<ToadieRegistrySourceStatus source={{ ...SOURCE, status, cache: { ...SOURCE.cache, state } }} />);
    expect(screen.getByText(label)).toBeInTheDocument();
  });
});
