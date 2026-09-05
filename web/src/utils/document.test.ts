import { afterEach, describe, expect, test, vi } from "vitest";
import { blankTemplate, detectFormat, documentFileName, downloadText, normalizeSourceUrl, utf8Length } from "./document";

describe("document", () => {
  afterEach(() => vi.restoreAllMocks());

  test("detects JSON by the first significant character, YAML otherwise", () => {
    expect(detectFormat("  {\"a\":1}")).toBe("json");
    expect(detectFormat("[1]")).toBe("json");
    expect(detectFormat("openapi: 3.1.0")).toBe("yaml");
    expect(detectFormat("")).toBe("yaml");
  });

  test("counts UTF-8 bytes, not characters", () => {
    expect(utf8Length("abc")).toBe(3);
    expect(utf8Length("żółw")).toBe(7);
  });

  test("rewrites GitHub and GitLab browser links to raw files and leaves everything else alone", () => {
    expect(normalizeSourceUrl(" https://github.com/acme/orders/blob/main/openapi.yaml ")).toBe("https://raw.githubusercontent.com/acme/orders/main/openapi.yaml");
    expect(normalizeSourceUrl("https://gitlab.example.com/grp/proj/-/blob/main/a.yaml")).toBe("https://gitlab.example.com/grp/proj/-/raw/main/a.yaml");
    expect(normalizeSourceUrl("https://gitlab.example.com/-/blob/x")).toBe("https://gitlab.example.com/-/blob/x");
    expect(normalizeSourceUrl("https://raw.githubusercontent.com/a/b/main/c.yaml")).toBe("https://raw.githubusercontent.com/a/b/main/c.yaml");
    expect(normalizeSourceUrl("http://github.com/a/b/blob/main/c")).toBe("http://github.com/a/b/blob/main/c");
  });

  test("builds the server's download name and slugs the parts", () => {
    expect(documentFileName({ system: "Gate way", contract: "orders/api", version: "1.0.0", format: "yaml" })).toBe("Gate-way__orders-api__1.0.0.yaml");
    expect(documentFileName({ system: "---", contract: "x", version: "1", format: "json" })).toBe("document__x__1.json");
  });

  test("the templates pass their type gate and carry the version and title", () => {
    expect(blankTemplate("OPENAPI", "1.0.0", "Orders")).toMatch(/^openapi: 3\.1\.0\n/);
    expect(blankTemplate("ASYNCAPI", "1.0.0", "Orders")).toContain("asyncapi: 3.0.0");
    const odcs = blankTemplate("ODCS", "2.0.0", "Order Views");
    expect(odcs).toContain("kind: DataContract");
    expect(odcs).toContain("id: order-views");
    expect(odcs).toContain("version: 2.0.0");
  });

  test("downloadText hands the browser an object URL on a transient anchor", () => {
    const create = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:x");
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const click = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    downloadText("a.yaml", "text", "application/yaml");
    expect(create).toHaveBeenCalledTimes(1);
    expect(click).toHaveBeenCalledTimes(1);
    expect(revoke).toHaveBeenCalledWith("blob:x");
    expect(document.querySelector("a[download]")).toBeNull();
  });
});
