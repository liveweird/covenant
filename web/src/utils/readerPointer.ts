/**
 * A finding's JSON pointer → the rendered element it belongs to. Every rendered card carries
 * `data-pointer`; a finding deep inside a response schema has no card of its own, so the
 * candidates are the pointer and its ancestors, longest first — the first that exists wins.
 * Attributes are compared directly (not through a CSS selector) so pointer characters need no escaping.
 */
function pointerCandidates(path: string): string[] {
  if (!path.startsWith("/")) return [];
  const segments = path.split("/").slice(1);
  const out: string[] = [];
  for (let i = segments.length; i >= 1; i--) out.push("/" + segments.slice(0, i).join("/"));
  return out;
}

export function findPointerElement(root: ParentNode, path: string): HTMLElement | null {
  const candidates = pointerCandidates(path);
  if (candidates.length === 0) return null;
  const byPointer = new Map<string, HTMLElement>();
  for (const el of root.querySelectorAll<HTMLElement>("[data-pointer]")) {
    const pointer = el.getAttribute("data-pointer");
    if (pointer != null && !byPointer.has(pointer)) byPointer.set(pointer, el);
  }
  for (const candidate of candidates) {
    const el = byPointer.get(candidate);
    if (el) return el;
  }
  return null;
}
