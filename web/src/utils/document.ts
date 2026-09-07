import type { ContractType } from "../api/contracts";
import type { DocumentFormat } from "../api/versions";

/**
 * What a document hand-off via `location.state` carries into an editor screen: the sync modal's
 * repo copy (`SyncVersionModal.tsx`) and the Infer page's generated draft (`InferContract.tsx`)
 * both seed a document screen this way. `type` rides along only when the destination does not
 * already know it (a new contract via `ImportContract`) — `NewVersion` ignores it, since the
 * contract's type is fixed.
 */
export type SeededDocument = { content: string; sourceUrl: string | null; type?: ContractType };

/** The server's rule (DocumentParser.detectFormat): a `{`/`[` first significant character is JSON. */
export function detectFormat(text: string): DocumentFormat {
  const first = text.trimStart()[0];
  return first === "{" || first === "[" ? "json" : "yaml";
}

/** Mirrors `contracts.maxDocumentBytes` (2 MiB) — larger texts are refused before parsing. */
export const MAX_DOCUMENT_BYTES = 2 * 1024 * 1024;

export function utf8Length(text: string): number {
  return new TextEncoder().encode(text).length;
}

/**
 * Convenience rewrites of Git-hosting BROWSER links to their raw-file form, applied before the
 * server-side fetch (which needs the actual file, not the HTML viewer): GitHub `/blob/` links
 * become raw.githubusercontent.com, GitLab `/-/blob/` becomes `/-/raw/`. Anything else — raw
 * links included — passes through untouched.
 */
/** The server's static source-reference rule, mirrored: an absolute https URL without credentials. */
export function isSourceUrl(url: string): boolean {
  try {
    const parsed = new URL(url);
    return parsed.protocol === "https:" && parsed.username === "" && parsed.password === "" && url.length <= 2048;
  } catch {
    return false;
  }
}

export function normalizeSourceUrl(url: string): string {
  const trimmed = url.trim();
  const github = /^https:\/\/github\.com\/([^/]+)\/([^/]+)\/blob\/(.+)$/.exec(trimmed);
  if (github) return `https://raw.githubusercontent.com/${github[1]}/${github[2]}/${github[3]}`;
  // GitLab: `https://host/group/project/-/blob/<ref>/<path>` → `/-/raw/` (a plain scan — no backtracking regex).
  const marker = "/-/blob/";
  const at = trimmed.indexOf(marker);
  const pathStart = trimmed.indexOf("/", "https://".length);
  if (trimmed.startsWith("https://") && pathStart !== -1 && pathStart + 1 < at) {
    return `${trimmed.slice(0, at)}/-/raw/${trimmed.slice(at + marker.length)}`;
  }
  return trimmed;
}

function trimDashes(s: string): string {
  let start = 0;
  let end = s.length;
  while (start < end && s[start] === "-") start++;
  while (end > start && s[end - 1] === "-") end--;
  return s.slice(start, end);
}

/** `<system>__<contract>__<version>.<ext>` — the server's download name, mirrored for client-side saves. */
export function documentFileName(parts: { system: string; contract: string; version: string; format: DocumentFormat }): string {
  const slug = (s: string) => trimDashes(s.trim().replace(/[^A-Za-z0-9._-]+/g, "-")) || "document";
  return `${slug(parts.system)}__${slug(parts.contract)}__${slug(parts.version)}.${parts.format}`;
}

/** Hands the browser a text file to save — an object URL on a transient anchor. */
export function downloadText(fileName: string, text: string, mimeType: string) {
  const url = URL.createObjectURL(new Blob([text], { type: mimeType }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  URL.revokeObjectURL(url);
}

/** A starting document per type — enough structure to pass the type gate and name the version. */
export function blankTemplate(type: "OPENAPI" | "ASYNCAPI" | "ODCS", version: string, title: string): string {
  switch (type) {
    case "OPENAPI":
      return `openapi: 3.1.0\ninfo:\n  title: ${title}\n  version: ${version}\n  description: ""\npaths: {}\n`;
    case "ASYNCAPI":
      return `asyncapi: 3.0.0\ninfo:\n  title: ${title}\n  version: ${version}\n  description: ""\nchannels: {}\noperations: {}\n`;
    default:
      return `apiVersion: v3.0.2\nkind: DataContract\nid: ${title.toLowerCase().replace(/[^a-z0-9]+/g, "-")}\nname: ${title}\nversion: ${version}\nstatus: draft\ndescription:\n  purpose: ""\nschema: []\n`;
  }
}
