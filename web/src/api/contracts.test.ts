import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";
import { checkImportContracts, fetchContractUrl, getContractTree, importContracts, listContracts } from "./contracts";
import { checkDocument, getVersionContent, softRejectionFindings } from "./versions";
import { listAllReleaseLines, updateReleaseLine } from "./releaseLines";
import { ApiError } from "./http";
import { jsonResponse } from "../test/http";
import { CLEAN_REPORT, signIn, SOFT_ERROR } from "../test/contractsFixtures";

describe("contracts + versions API wrappers", () => {
  let mockFetch: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    mockFetch = vi.fn();
    vi.stubGlobal("fetch", mockFetch);
    signIn();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  test("the list repeats type/lifecycle as IN params, omits hasErrors=false, and the tree drops an empty query", async () => {
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { items: [], page: 1, pageSize: 20, total: 0 })));
    await listContracts({ page: 1, pageSize: 20, sort: "name", types: ["OPENAPI", "ODCS"], lifecycles: ["ACTIVE"], hasErrors: false, q: "" });
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/contracts?page=1&pageSize=20&sort=name&type=OPENAPI&type=ODCS&lifecycle=ACTIVE");
    await listContracts({ page: 1, pageSize: 20, hasErrors: true, ownerTeamId: 3 });
    expect(mockFetch.mock.calls[1][0]).toBe("/api/v1/contracts?page=1&pageSize=20&ownerTeamId=3&hasErrors=true");
    mockFetch.mockImplementation(() => Promise.resolve(jsonResponse(200, { domains: [] })));
    await getContractTree();
    expect(mockFetch.mock.calls[2][0]).toBe("/api/v1/contracts/tree");
    await getContractTree({ systemId: 7 });
    expect(mockFetch.mock.calls[3][0]).toBe("/api/v1/contracts/tree?systemId=7");
  });

  test("import, dry run and fetch unwrap their envelopes", async () => {
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { results: [{ index: 0, status: "CREATED" }] }));
    expect(await importContracts([])).toEqual([{ index: 0, status: "CREATED" }]);
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { results: [] }));
    expect(await checkImportContracts([])).toEqual([]);
    expect(mockFetch.mock.calls[1][0]).toBe("/api/v1/contracts/import/check");
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { content: "openapi: 3.1.0" }));
    expect(await fetchContractUrl("https://x/y")).toBe("openapi: 3.1.0");
    expect(JSON.parse(mockFetch.mock.calls[2][1].body)).toEqual({ url: "https://x/y" });
  });

  test("raw content comes back as text; a failure is an ApiError", async () => {
    mockFetch.mockResolvedValueOnce(new Response("openapi: 3.1.0\n", { status: 200, headers: { "Content-Type": "application/yaml" } }));
    expect(await getVersionContent(5, 11)).toBe("openapi: 3.1.0\n");
    mockFetch.mockResolvedValueOnce(jsonResponse(404, { title: "Not Found", status: 404 }));
    await expect(getVersionContent(5, 12)).rejects.toBeInstanceOf(ApiError);
  });

  test("release-line transport pages to total and sends a full replacement", async () => {
    const line = {
      id: 1, contractId: 5, major: 1, supportStatus: "SUPPORTED", supportEndsOn: null, supportPolicy: null,
      recommendedVersionId: null, latestVersion: null, recommendedVersion: null, versionCount: 0, updatedAt: 1,
    };
    mockFetch
      .mockResolvedValueOnce(jsonResponse(200, { items: [line], page: 1, pageSize: 100, total: 2 }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [{ ...line, id: 2, major: 0 }], page: 2, pageSize: 100, total: 2 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    expect((await listAllReleaseLines(5)).map((item) => item.major)).toEqual([1, 0]);
    expect(mockFetch.mock.calls[0][0]).toBe("/api/v1/contracts/5/release-lines?page=1&pageSize=100&sort=-major");
    expect(mockFetch.mock.calls[1][0]).toBe("/api/v1/contracts/5/release-lines?page=2&pageSize=100&sort=-major");
    const body = { supportStatus: "MAINTENANCE" as const, supportEndsOn: "2027-12-31", supportPolicy: "Critical fixes", recommendedVersionId: 10 };
    await updateReleaseLine(5, 1, body);
    expect(mockFetch.mock.calls[2][0]).toBe("/api/v1/contracts/5/release-lines/1");
    expect(JSON.parse(mockFetch.mock.calls[2][1].body)).toEqual(body);
  });

  test("softRejectionFindings re-checks only a strict save's blocking-findings 400 and returns the soft errors", async () => {
    const doc = { type: "OPENAPI" as const, content: "x" };
    expect(await softRejectionFindings(new Error("net"), doc)).toBeNull();
    expect(await softRejectionFindings(new ApiError(409, { detail: "exists" }), doc)).toBeNull();
    expect(await softRejectionFindings(new ApiError(400, { detail: "Version v1 is not valid SemVer" }), doc)).toBeNull();
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { ...CLEAN_REPORT, findings: [SOFT_ERROR, { ...SOFT_ERROR, severity: "WARN" }], errors: 1, warnings: 1 }));
    const found = await softRejectionFindings(new ApiError(400, { detail: "The document has 1 blocking finding(s): OAS_PARSE: paths is required" }), doc);
    expect(found).toEqual([SOFT_ERROR]);
    expect(mockFetch.mock.calls.at(-1)?.[0]).toBe("/api/v1/contracts/versions/check");
    // A 400 that names findings but whose re-check shows none soft (e.g. all HARD) is not waivable.
    mockFetch.mockResolvedValueOnce(jsonResponse(200, { ...CLEAN_REPORT, findings: [{ ...SOFT_ERROR, source: "SYNTAX" }], errors: 1 }));
    expect(await softRejectionFindings(new ApiError(400, { detail: "blocking finding" }), doc)).toBeNull();
    mockFetch.mockResolvedValueOnce(jsonResponse(200, CLEAN_REPORT));
    expect((await checkDocument(doc)).findings).toEqual([]);
  });
});
