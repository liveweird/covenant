import { Badge, type MantineSize } from "@mantine/core";
import { methodColor } from "../utils/httpMethods";

/** The HTTP method, text-first — the colour only echoes the word. */
export default function MethodBadge({ method, size = "sm" }: { method: string; size?: MantineSize }) {
  return (
    <Badge color={methodColor(method)} variant="light" size={size} radius="sm" ff="monospace" style={{ minWidth: 64, textTransform: "uppercase" }}>
      {method}
    </Badge>
  );
}
