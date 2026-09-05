import { useState } from "react";
import { Button, Menu } from "@mantine/core";
import { IconArrowsDiff, IconCloudDownload, IconDots, IconDownload, IconLink, IconPencil, IconRefresh, IconTrash } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import type { ContractResponse, Lifecycle } from "../api/contracts";
import type { VersionResponse } from "../api/versions";
import { versionDiffPath } from "../utils/contractLinks";
import { isDeletable } from "../utils/lifecycle";
import LifecycleActions from "./LifecycleActions";
import SourceUrlModal from "./SourceUrlModal";
import SyncVersionModal from "./SyncVersionModal";

/**
 * The version page's header row: the lifecycle moves (writers), Edit (writers, while the text
 * is editable), and the More menu — Download and Compare for everyone, Link source, Sync from
 * source (once linked), Re-run checks and Delete (a DRAFT) for writers. Everything but Edit is
 * disabled while the document is being edited: the operations act on the STORED text. The two
 * source modals live here with their open state, beside the items that open them.
 */
export default function VersionHeaderActions({
  contract,
  version,
  editing,
  canEdit,
  latestId,
  transitionPending,
  recheckPending,
  onTransition,
  onEdit,
  onDownload,
  onRecheck,
  onDelete,
  onSynced,
}: {
  contract: ContractResponse;
  version: VersionResponse;
  editing: boolean;
  canEdit: boolean;
  latestId: number | null;
  transitionPending: boolean;
  recheckPending: boolean;
  onTransition: (to: Lifecycle) => void;
  onEdit: () => void;
  onDownload: () => void;
  onRecheck: () => void;
  onDelete: () => void;
  /** After a successful sync — the page refreshes its queries. */
  onSynced: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const [linking, setLinking] = useState(false);
  const [syncing, setSyncing] = useState(false);
  return (
    <>
      <SourceUrlModal contractId={contract.id} version={linking ? version : null} onClose={() => setLinking(false)} />
      <SyncVersionModal contract={contract} version={syncing ? version : null} onClose={() => setSyncing(false)} onSynced={onSynced} />
      {contract.canWrite && !editing && (
        <LifecycleActions lifecycle={version.lifecycle} version={version.version} pending={transitionPending} onTransition={onTransition} />
      )}
      {canEdit && !editing && (
        <Button variant="default" leftSection={<IconPencil size={16} />} onClick={onEdit}>
          {t("versions.editDocument")}
        </Button>
      )}
      <Menu>
        <Menu.Target>
          <Button variant="default" leftSection={<IconDots size={16} />} aria-label={t("contracts.moreActions")} disabled={editing}>
            {t("contracts.more")}
          </Button>
        </Menu.Target>
        <Menu.Dropdown>
          <Menu.Item leftSection={<IconDownload size={14} />} onClick={onDownload}>
            {t("versions.download")}
          </Menu.Item>
          {latestId != null && latestId !== version.id && (
            <Menu.Item component={RouterLink} to={versionDiffPath(contract.id, version.id, latestId)} leftSection={<IconArrowsDiff size={14} />}>
              {t("versions.compareWithLatest")}
            </Menu.Item>
          )}
          {contract.canWrite && (
            <Menu.Item leftSection={<IconLink size={14} />} onClick={() => setLinking(true)}>
              {t("versions.sourceLink.action")}
            </Menu.Item>
          )}
          {contract.canWrite && version.sourceUrl != null && (
            <Menu.Item leftSection={<IconCloudDownload size={14} />} onClick={() => setSyncing(true)}>
              {t("versions.sync.action")}
            </Menu.Item>
          )}
          {contract.canWrite && (
            <Menu.Item leftSection={<IconRefresh size={14} />} onClick={onRecheck} disabled={recheckPending}>
              {t("versions.recheck")}
            </Menu.Item>
          )}
          {contract.canWrite && isDeletable(version.lifecycle) && (
            <>
              <Menu.Divider />
              <Menu.Item color="red" leftSection={<IconTrash size={14} />} onClick={onDelete}>
                {t("common.action.delete")}
              </Menu.Item>
            </>
          )}
        </Menu.Dropdown>
      </Menu>
    </>
  );
}
