import { afterEach, describe, expect, test } from "vitest";
import { act, renderHook } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import type { ReactNode } from "react";
import type { Lifecycle } from "../api/contracts";
import { useVersionView } from "./useVersionView";

function wrapperAt(route: string) {
  return ({ children }: { children: ReactNode }) => <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>;
}

describe("useVersionView", () => {
  afterEach(() => localStorage.clear());

  test("the lifecycle default: Source for an editable version, Reader for a published one — frozen once known", () => {
    const { result, rerender } = renderHook(({ lifecycle, editing }: { lifecycle: Lifecycle | undefined; editing: boolean }) => useVersionView(lifecycle, editing), {
      wrapper: wrapperAt("/contracts/5/versions/10"),
      initialProps: { lifecycle: undefined as Lifecycle | undefined, editing: false },
    });
    expect(result.current.view).toBe("source");
    rerender({ lifecycle: "ACTIVE", editing: false });
    expect(result.current.view).toBe("reader");
    rerender({ lifecycle: "DRAFT", editing: false });
    expect(result.current.view).toBe("reader");
    rerender({ lifecycle: "ACTIVE", editing: true });
    expect(result.current.view).toBe("source");
    const fresh = renderHook(() => useVersionView("DRAFT", false), { wrapper: wrapperAt("/x") });
    expect(fresh.result.current.view).toBe("source");
  });

  test("a chosen view persists and wins over the lifecycle; the URL param wins without persisting", () => {
    const { result } = renderHook(() => useVersionView("ACTIVE", false), { wrapper: wrapperAt("/x") });
    act(() => result.current.setView("source"));
    expect(result.current.view).toBe("source");
    expect(JSON.parse(localStorage.getItem("covenant.viewSettings.version.view") ?? "null")).toBe("source");
    const remembered = renderHook(() => useVersionView("ACTIVE", false), { wrapper: wrapperAt("/x") });
    expect(remembered.result.current.view).toBe("source");
    const linked = renderHook(() => useVersionView("ACTIVE", false), { wrapper: wrapperAt("/x?view=reader") });
    expect(linked.result.current.view).toBe("reader");
    expect(JSON.parse(localStorage.getItem("covenant.viewSettings.version.view") ?? "null")).toBe("source");
    const bogus = renderHook(() => useVersionView("DRAFT", false), { wrapper: wrapperAt("/x?view=nope") });
    expect(bogus.result.current.view).toBe("source");
  });
});
