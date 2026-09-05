import { Badge, type MantineColor, type MantineSize } from "@mantine/core";
import type { ReactNode } from "react";

/** A value badge in the reader — the word as written (no uppercase transform), gray unless a verdict colour applies. */
export default function PlainBadge({
  children,
  outline = false,
  color = "gray",
  size = "xs",
  ml,
}: {
  children: ReactNode;
  outline?: boolean;
  color?: MantineColor;
  size?: MantineSize;
  ml?: number;
}) {
  return (
    <Badge variant={outline ? "outline" : "light"} color={color} size={size} ml={ml} style={{ textTransform: "none" }}>
      {children}
    </Badge>
  );
}
