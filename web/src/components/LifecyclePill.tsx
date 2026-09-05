import { Badge, type MantineSize } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { Lifecycle } from "../api/contracts";
import { LIFECYCLE_COLOR } from "../utils/lifecycle";

/** A version's lifecycle in the app-wide colour vocabulary (RETIRED is the outlined, spent state). */
export default function LifecyclePill({ lifecycle, size = "sm" }: { lifecycle: Lifecycle; size?: MantineSize }) {
  const { t } = useTranslation();
  return (
    <Badge color={LIFECYCLE_COLOR[lifecycle]} variant={lifecycle === "RETIRED" ? "outline" : "light"} size={size}>
      {t(`versions.lifecycle.${lifecycle}`)}
    </Badge>
  );
}
