import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Button, Tooltip } from "@mantine/core";
import { IconBell, IconBellRinging } from "@tabler/icons-react";
import { useQueryClient } from "@tanstack/react-query";
import { subscribeContract, unsubscribeContract, type ContractResponse } from "../api/contracts";
import { showSuccessToast } from "../utils/toast";

/**
 * Follow / Following on the contract page: every event on the contract then reaches the
 * follower's bell. The count shows how many people watch it. Toggling invalidates the
 * ["contracts"] prefix so the flag and the count come back from the server.
 */
export default function FollowButton({ contract }: { contract: ContractResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const [busy, setBusy] = useState(false);
  const label = contract.subscribed ? t("contracts.follow.following") : t("contracts.follow.follow");

  async function toggle() {
    setBusy(true);
    try {
      if (contract.subscribed) await unsubscribeContract(contract.id);
      else await subscribeContract(contract.id);
      await queryClient.invalidateQueries({ queryKey: ["contracts"] });
      showSuccessToast(contract.subscribed ? t("contracts.follow.follow") : t("contracts.follow.following"));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Tooltip label={t("contracts.follow.count", { count: contract.subscriberCount })}>
      <Button
        variant={contract.subscribed ? "light" : "default"}
        leftSection={contract.subscribed ? <IconBellRinging size={16} /> : <IconBell size={16} />}
        onClick={() => void toggle()}
        loading={busy}
        aria-pressed={contract.subscribed}
      >
        {label} · {contract.subscriberCount}
      </Button>
    </Tooltip>
  );
}
