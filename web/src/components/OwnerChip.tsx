import { Anchor, Badge, Group, Text } from "@mantine/core";
import { IconUser, IconUsersGroup } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { OwnerRef } from "../api/contracts";
import { teamPath } from "../utils/teamLinks";

/** Who owns a contract: a team (linked to its roster) or a person; a soft-deleted owner stays, flagged. */
export default function OwnerChip({ owner }: { owner: OwnerRef }) {
  const { t } = useTranslation();
  const Icon = owner.kind === "TEAM" ? IconUsersGroup : IconUser;
  return (
    <Group gap={6} wrap="nowrap">
      <Icon size={14} stroke={1.5} aria-label={t(`contracts.owner.${owner.kind}`)} />
      {owner.kind === "TEAM" && !owner.deleted ? (
        <Anchor component={RouterLink} to={teamPath(owner.id)} size="sm">
          {owner.name}
        </Anchor>
      ) : (
        <Text size="sm" c={owner.deleted ? "dimmed" : undefined}>
          {owner.name}
        </Text>
      )}
      {owner.deleted && (
        <Badge size="xs" variant="outline" color="gray">
          {t("contracts.owner.deleted")}
        </Badge>
      )}
    </Group>
  );
}
