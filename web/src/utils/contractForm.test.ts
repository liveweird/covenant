import { describe, expect, test } from "vitest";
import i18n from "../i18n";
import { ApiError } from "../api/http";
import {
  contractFormValidation,
  contractSaveErrorMessage,
  fromContractResponse,
  ownerValueOf,
  splitOwnerValue,
  systemOptions,
  toContractCreateRequest,
} from "./contractForm";
import { CONTRACT, SYSTEMS_PAGE } from "../test/contractsFixtures";

const t = i18n.t.bind(i18n);

describe("contractForm", () => {
  test("owner values round-trip through the TEAM:/USER: spelling", () => {
    expect(ownerValueOf({ kind: "TEAM", id: 3 })).toBe("TEAM:3");
    expect(splitOwnerValue("TEAM:3")).toEqual({ ownerTeamId: 3, ownerUserId: null });
    expect(splitOwnerValue("USER:9")).toEqual({ ownerTeamId: null, ownerUserId: 9 });
    expect(splitOwnerValue("")).toEqual({ ownerTeamId: null, ownerUserId: null });
    expect(splitOwnerValue("TEAM:x")).toEqual({ ownerTeamId: null, ownerUserId: null });
  });

  test("maps the form to the create request and a response back to the form", () => {
    expect(toContractCreateRequest({ systemId: "7", type: "ODCS", name: " views ", description: "  ", owner: "USER:2" })).toEqual({
      systemId: 7, type: "ODCS", name: "views", description: null, ownerTeamId: null, ownerUserId: 2,
    });
    expect(fromContractResponse(CONTRACT)).toEqual({ systemId: "7", type: "OPENAPI", name: "orders-api", description: "Orders", owner: "TEAM:3" });
  });

  test("validation mirrors the server, with the owner and system rules switchable", () => {
    const rules = contractFormValidation(t);
    expect(rules.systemId(null)).toBe("Pick a system");
    expect(rules.systemId("7")).toBeNull();
    expect(rules.name("")).toBe("Name must be 1–100 characters");
    expect(rules.name("x".repeat(101))).toBe("Name must be 1–100 characters");
    expect(rules.name("ok")).toBeNull();
    expect(rules.description("x".repeat(2001))).toBe("Description must be at most 2000 characters");
    expect(rules.owner(null)).toBe("Pick an owner");
    const relaxed = contractFormValidation(t, { withOwner: false, withSystem: false });
    expect(relaxed.owner(null)).toBeNull();
    expect(relaxed.systemId(null)).toBeNull();
  });

  test("groups systems under their domains, sorted", () => {
    const options = systemOptions([...SYSTEMS_PAGE.items, { ...SYSTEMS_PAGE.items[0], id: 8, domainId: 2, domainName: "Identity", name: "idp" }]);
    expect(options.map((g) => g.group)).toEqual(["Identity", "Payments"]);
    expect(options[1].items).toEqual([{ value: "7", label: "gateway" }]);
  });

  test("the save vocabulary", () => {
    expect(contractSaveErrorMessage(new ApiError(403, null), t)).toMatch(/cannot edit this contract/);
    expect(contractSaveErrorMessage(new ApiError(404, null), t)).toMatch(/no longer exists/);
    expect(contractSaveErrorMessage(new ApiError(400, null), t)).toBe("One or more fields are invalid");
    expect(contractSaveErrorMessage(new ApiError(500, null), t)).toBe("Save failed (500)");
    expect(contractSaveErrorMessage(new DOMException("t", "TimeoutError"), t)).toMatch(/timed out/);
    expect(contractSaveErrorMessage(new Error("x"), t)).toMatch(/connection/);
  });
});
