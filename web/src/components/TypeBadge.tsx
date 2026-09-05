import { Badge, type MantineSize } from "@mantine/core";
import { IconApi, IconBroadcast, IconTable, type Icon } from "@tabler/icons-react";
import type { ContractType } from "../api/contracts";
import { CONTRACT_TYPE_LABEL } from "../utils/contractForm";

const ICONS: Record<ContractType, Icon> = {
  OPENAPI: IconApi,
  ASYNCAPI: IconBroadcast,
  ODCS: IconTable,
};

/** The contract type as a neutral outline badge — a category, never a status (no colour of its own). */
export default function TypeBadge({ type, size = "sm" }: { type: ContractType; size?: MantineSize }) {
  const Icon = ICONS[type];
  return (
    <Badge variant="outline" color="gray" size={size} leftSection={<Icon size={12} stroke={1.75} />} style={{ textTransform: "none" }}>
      {CONTRACT_TYPE_LABEL[type]}
    </Badge>
  );
}
