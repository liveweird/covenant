// A pure, dependency-free HAR 1.2 (http://www.softwareishard.com/blog/har-12-spec/) reader for
// the Infer page's "HAR file" source: only request/response SHAPE survives (method, url minus
// query/search, header NAMES, JSON-like bodies) — no header VALUE beyond the Authorization
// scheme word ever leaves this file, and secret-shaped query values are blanked before they do.
// The exchange shape IS the server's `HttpExchangeSample` wire shape (api/infer.ts) — every field
// here is present (never optional), which satisfies that type structurally.

import type { HttpExchangeSample } from "../api/infer";

export type HarReadResult = {
  origins: { origin: string; count: number }[];
  exchanges: HttpExchangeSample[];
  total: number;
};

const MAX_TEXT_BYTES = 30 * 1024 * 1024;
const MAX_BODY_BYTES = 1024 * 1024;
const SECRET_QUERY_NAME = /token|key|secret|password|auth|session|signature|sig/i;
const AUTH_SCHEMES = new Set(["bearer", "basic", "digest"]);

type HarHeader = { name?: unknown; value?: unknown };
type HarQueryParam = { name?: unknown; value?: unknown };
type HarContent = { text?: unknown; mimeType?: unknown; encoding?: unknown };
type HarPostData = { text?: unknown; mimeType?: unknown };
type HarRequest = { method?: unknown; url?: unknown; headers?: unknown; queryString?: unknown; postData?: unknown };
type HarResponse = { status?: unknown; headers?: unknown; content?: unknown };
type HarEntry = { request?: unknown; response?: unknown };
type HarFile = { log?: { entries?: unknown } };

function mediaType(contentType: string | null): string | null {
  if (contentType == null) return null;
  const bare = contentType.split(";")[0]?.trim().toLowerCase();
  return bare === "" ? null : (bare ?? null);
}

function isJsonLike(mime: string | null): boolean {
  return mime != null && (mime === "application/json" || mime.endsWith("+json"));
}

function headerNames(headers: unknown): string[] {
  if (!Array.isArray(headers)) return [];
  const seen = new Set<string>();
  for (const h of headers as HarHeader[]) {
    const name = typeof h.name === "string" ? h.name.toLowerCase() : null;
    if (name != null) seen.add(name);
  }
  return [...seen];
}

function headerValue(headers: unknown, name: string): string | null {
  if (!Array.isArray(headers)) return null;
  for (const h of headers as HarHeader[]) {
    if (typeof h.name === "string" && h.name.toLowerCase() === name && typeof h.value === "string") return h.value;
  }
  return null;
}

function authorizationScheme(headers: unknown): string | null {
  const value = headerValue(headers, "authorization");
  if (value == null) return null;
  const word = value.trim().split(/\s+/)[0]?.toLowerCase();
  if (word == null || word === "") return "other";
  return AUTH_SCHEMES.has(word) ? word : "other";
}

function queryParams(queryString: unknown): Record<string, string> {
  const out: Record<string, string> = {};
  if (!Array.isArray(queryString)) return out;
  for (const q of queryString as HarQueryParam[]) {
    if (typeof q.name !== "string" || typeof q.value !== "string") continue;
    if (q.name in out) continue;
    out[q.name] = SECRET_QUERY_NAME.test(q.name) ? "" : q.value;
  }
  return out;
}

function byteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

/** `postData.text` when JSON-like and within the size cap, else `null`. */
function requestBody(postData: unknown, mime: string | null): string | null {
  if (!isJsonLike(mime)) return null;
  const data = postData as HarPostData | undefined;
  const text = data?.text;
  if (typeof text !== "string" || byteLength(text) > MAX_BODY_BYTES) return null;
  return text;
}

/** `content.text`, base64-decoded when `encoding === "base64"`, when JSON-like and decodable. */
function responseBody(content: unknown, mime: string | null): string | null {
  if (!isJsonLike(mime)) return null;
  const c = content as HarContent | undefined;
  const raw = c?.text;
  if (typeof raw !== "string") return null;
  const encoded = c?.encoding === "base64";
  let text: string;
  try {
    text = encoded ? atob(raw) : raw;
  } catch {
    return null;
  }
  return byteLength(text) > MAX_BODY_BYTES ? null : text;
}

function parsedUrl(rawUrl: unknown): URL | null {
  if (typeof rawUrl !== "string") return null;
  try {
    const url = new URL(rawUrl);
    return url.protocol === "http:" || url.protocol === "https:" ? url : null;
  } catch {
    return null;
  }
}

function isBodylessAnswerToJson(status: number, requestMime: string | null): boolean {
  return (status === 204 || status >= 300) && isJsonLike(requestMime);
}

function toExchange(entry: HarEntry): HttpExchangeSample | null {
  const request = entry.request as HarRequest | undefined;
  const response = entry.response as HarResponse | undefined;
  if (request == null || response == null) return null;
  const method = typeof request.method === "string" ? request.method.toUpperCase() : "";
  if (method === "" || method === "OPTIONS") return null;
  const url = parsedUrl(request.url);
  if (url == null) return null;
  const status = typeof response.status === "number" ? response.status : 0;

  const requestMime = mediaType(headerValue(request.headers, "content-type"));
  const acceptMime = mediaType(headerValue(request.headers, "accept"));
  const responseContent = response.content as HarContent | undefined;
  const responseMime = mediaType(typeof responseContent?.mimeType === "string" ? responseContent.mimeType : null);

  const jsonRelevant = isJsonLike(requestMime) || isJsonLike(responseMime) || isBodylessAnswerToJson(status, requestMime ?? acceptMime);
  if (!jsonRelevant) return null;

  return {
    method,
    url: url.origin + url.pathname,
    query: queryParams(request.queryString),
    requestHeaders: headerNames(request.headers),
    authorizationScheme: authorizationScheme(request.headers),
    requestContentType: requestMime,
    requestBody: requestBody(request.postData, requestMime),
    status,
    responseHeaders: headerNames(response.headers),
    responseContentType: responseMime,
    responseBody: responseBody(responseContent, responseMime),
  };
}

function originCounts(exchanges: readonly HttpExchangeSample[]): { origin: string; count: number }[] {
  const counts = new Map<string, number>();
  for (const exchange of exchanges) {
    const origin = new URL(exchange.url).origin;
    counts.set(origin, (counts.get(origin) ?? 0) + 1);
  }
  return [...counts.entries()].map(([origin, count]) => ({ origin, count })).sort((a, b) => b.count - a.count);
}

/**
 * Reads a HAR 1.2 export into the exchange shapes the inference engine accepts. Throws a plain
 * `Error("too large")` above 30 MiB of text (before parsing) and `Error("not a HAR file")` when
 * `log.entries` is missing — the caller localises both.
 */
export function readHar(text: string): HarReadResult {
  if (byteLength(text) > MAX_TEXT_BYTES) throw new Error("too large");
  const parsed = JSON.parse(text) as HarFile;
  const entries = parsed.log?.entries;
  if (!Array.isArray(entries)) throw new Error("not a HAR file");
  const exchanges = (entries as HarEntry[]).map(toExchange).filter((e): e is HttpExchangeSample => e != null);
  return { origins: originCounts(exchanges), exchanges, total: entries.length };
}
