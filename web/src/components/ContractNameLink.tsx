import { Anchor } from "@mantine/core";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { contractPath } from "../utils/contractLinks";

/** A contract's name as the way into its page — a real link with the interpolated accessible name tests locate. */
export default function ContractNameLink({ id, name }: { id: number; name: string }) {
  const { t } = useTranslation();
  return (
    <Anchor component={RouterLink} to={contractPath(id)} fw={500} size="sm" aria-label={t("contracts.openAria", { name })}>
      {name}
    </Anchor>
  );
}
