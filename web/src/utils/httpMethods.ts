/** The reader's method colours — text-first badges, the app's vocabulary: teal reads, the brand hue creates, yellow changes, red removes. */
const METHOD_COLOR: Record<string, string> = {
  GET: "teal",
  HEAD: "teal",
  OPTIONS: "gray",
  POST: "covenant",
  PUT: "yellow",
  PATCH: "yellow",
  DELETE: "red",
  TRACE: "gray",
};

export function methodColor(method: string): string {
  return METHOD_COLOR[method.toUpperCase()] ?? "gray";
}

/** Response status classes → the finding colours: 2xx teal (success), 3xx gray, 4xx orange, 5xx red, `default` gray. */
export function statusClassColor(status: string): string {
  const first = status.charAt(0);
  if (first === "2") return "teal";
  if (first === "4") return "orange";
  if (first === "5") return "red";
  return "gray";
}
