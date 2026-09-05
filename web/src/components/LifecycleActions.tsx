import { useState } from "react";
import { Button, Group } from "@mantine/core";
import { useTranslation } from "react-i18next";
import type { Lifecycle } from "../api/contracts";
import { isIrreversibleTransition, TRANSITIONS } from "../utils/lifecycle";

type Irreversible = "DEPRECATED" | "RETIRED";
import ConfirmActionModal from "./ConfirmActionModal";

/**
 * The transition buttons for one version — exactly the moves the lifecycle allows from its
 * current state (utils/lifecycle.ts mirrors the server's matrix). ACTIVE is the primary
 * (filled) step; DEPRECATE/RETIRE take the version out of circulation and confirm first.
 * Disabled while the document has unsaved edits: a transition applies to the STORED text.
 */
export default function LifecycleActions({
  lifecycle,
  version,
  disabled = false,
  pending = false,
  onTransition,
}: {
  lifecycle: Lifecycle;
  version: string;
  disabled?: boolean;
  pending?: boolean;
  onTransition: (to: Lifecycle) => void;
}) {
  const { t } = useTranslation();
  const [confirming, setConfirming] = useState<Irreversible | null>(null);
  const targets = TRANSITIONS[lifecycle];
  if (targets.length === 0) return null;
  return (
    <>
      <Group gap="xs">
        {targets.map((to) => (
          <Button
            key={to}
            size="xs"
            variant={to === "ACTIVE" ? "filled" : "default"}
            color={isIrreversibleTransition(to) ? "orange" : undefined}
            disabled={disabled || pending}
            loading={pending && confirming === null}
            onClick={() => (isIrreversibleTransition(to) ? setConfirming(to as Irreversible) : onTransition(to))}
          >
            {t(`versions.transition.${to}`)}
          </Button>
        ))}
      </Group>
      <ConfirmActionModal
        opened={confirming !== null}
        onClose={() => setConfirming(null)}
        title={confirming ? t(`versions.transitionConfirm.${confirming}.title`) : ""}
        message={confirming ? t(`versions.transitionConfirm.${confirming}.body`, { version }) : ""}
        cancelLabel={t("common.action.cancel")}
        confirmLabel={confirming ? t(`versions.transition.${confirming}`) : ""}
        confirmColor="orange"
        loading={pending}
        onConfirm={() => {
          if (confirming) onTransition(confirming);
          setConfirming(null);
        }}
      />
    </>
  );
}
