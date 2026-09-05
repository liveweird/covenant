import { Alert, Anchor, Drawer, Select, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { IconPlugConnected } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { ContractResponse } from "../api/contracts";
import { listEnvironments } from "../api/environments";
import { isAdmin } from "../api/session";
import { getTryCatalog } from "../api/tryIt";
import type { VersionResponse } from "../api/versions";
import { useStoredState } from "../hooks/useStoredState";
import { environmentsPath } from "../utils/environmentLinks";
import { loadErrorMessage } from "../utils/saveError";
import { hasTryTarget, tryTargetOf } from "../utils/tryIt";
import EmptyState from "./EmptyState";
import LoadingBlock from "./LoadingBlock";
import TryHttpPanel from "./TryHttpPanel";
import TryKafkaPanel from "./TryKafkaPanel";
import TrySqlPanel from "./TrySqlPanel";

const isString = (v: unknown): v is string => typeof v === "string";

/**
 * The try-it drawer: the version's try catalog (what the document offers) and the environments of
 * the contract's system that hold the leg's target — the last choice per system is remembered —
 * then the leg's panel. The server does every call; the drawer never reaches a target.
 */
export default function TryItDrawer({
  contract,
  version,
  opened,
  onClose,
}: {
  contract: ContractResponse;
  version: VersionResponse;
  opened: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const target = tryTargetOf(contract.type);
  const catalog = useQuery({
    queryKey: ["contracts", "try-catalog", contract.id, version.id, version.contentSha256],
    queryFn: () => getTryCatalog(contract.id, version.id),
    enabled: opened,
  });
  const environments = useQuery({
    queryKey: ["environments", "forSystem", contract.system.id],
    queryFn: () => listEnvironments({ page: 1, pageSize: 100, systemId: contract.system.id }),
    enabled: opened,
  });
  const [stored, setStored] = useStoredState(`tryIt.environment.${contract.system.id}`, "", isString);
  const usable = (environments.data?.items ?? []).filter((e) => hasTryTarget(e, target));
  const selected = usable.find((e) => String(e.id) === stored) ?? usable[0] ?? null;
  const error = catalog.error ?? environments.error;
  const empty = catalog.data && catalog.data.http.length === 0 && catalog.data.kafka.length === 0 && catalog.data.sql.length === 0;

  let body;
  if (catalog.isLoading || environments.isLoading) body = <LoadingBlock />;
  else if (error) {
    body = (
      <Alert color="red" variant="light" role="alert">
        {loadErrorMessage(error, t)}
      </Alert>
    );
  } else if (empty) body = <EmptyState icon={IconPlugConnected} label={t("tryIt.nothingToTry")} />;
  else if (usable.length === 0 || !selected) {
    body = (
      <Stack align="center" gap="xs" py="xl">
        <EmptyState icon={IconPlugConnected} label={t("tryIt.noEnvironments", { target: t(`tryIt.target.${target}`) })} />
        {isAdmin() && (
          <Anchor component={RouterLink} to={environmentsPath} size="sm">
            {t("tryIt.noEnvironmentsAdmin")}
          </Anchor>
        )}
      </Stack>
    );
  } else if (catalog.data) {
    body = (
      <Stack gap="md">
        <Select
          label={t("tryIt.environment")}
          description={t("tryIt.environmentHint")}
          data={usable.map((e) => ({ value: String(e.id), label: e.name }))}
          value={String(selected.id)}
          onChange={(v) => v && setStored(v)}
          allowDeselect={false}
        />
        {target === "http" && (
          <TryHttpPanel key={selected.id} contractId={contract.id} versionId={version.id} environmentId={selected.id} operations={catalog.data.http} />
        )}
        {target === "kafka" && (
          <TryKafkaPanel
            key={selected.id}
            contractId={contract.id}
            versionId={version.id}
            environmentId={selected.id}
            channels={catalog.data.kafka}
            canWrite={contract.canWrite}
          />
        )}
        {target === "postgres" && (
          <TrySqlPanel key={selected.id} contractId={contract.id} versionId={version.id} environmentId={selected.id} datasets={catalog.data.sql} />
        )}
      </Stack>
    );
  }

  return (
    <Drawer
      opened={opened}
      onClose={onClose}
      position="right"
      size="xl"
      closeOnClickOutside={false}
      title={t("tryIt.title", { name: contract.name, version: version.version })}
    >
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          {t("tryIt.intro")}
        </Text>
        {body}
      </Stack>
    </Drawer>
  );
}
