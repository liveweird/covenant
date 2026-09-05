import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useOs } from "@mantine/hooks";
import { useDebouncedValue } from "@mantine/hooks";
import { Spotlight, type SpotlightActionData, type SpotlightActionGroupData, type SpotlightFilterFunction } from "@mantine/spotlight";

type SpotlightActions = SpotlightActionData | SpotlightActionGroupData;
import { useQuery } from "@tanstack/react-query";
import { IconFileImport, IconFileText, IconPlus, IconSearch } from "@tabler/icons-react";
import { listContracts } from "../api/contracts";
import { isAdmin } from "../api/session";
import { contractPath, importContractPath, newContractPath } from "../utils/contractLinks";
import { ACCOUNT_NAV, visibleSections } from "../utils/navigation";
import { palette, paletteStore } from "../utils/commandPalette";
import { foldDiacritics } from "../utils/text";
import classes from "../theme.module.css";

/**
 * The command palette: ⌘K / Ctrl K, or the search-looking trigger in the header. Three groups —
 * every page the session may see (the same nav model as the sidebar plus the account leaves),
 * the contract actions (New contract, Import), and a server-side contract search (debounced,
 * two or more characters, under the `["contracts", "palette", …]` key so a mutation refreshes
 * it). Pages and actions filter client-side by folded label; the search group is the server's
 * answer and passes through the filter untouched. Renders BOTH the trigger and the Spotlight,
 * mounted once in the shell header. No `highlightQuery`: it splits a label into `<mark>` + text
 * fragments, and tests/e2e locate results by their full name.
 */
export default function CommandPalette() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const os = useOs();
  const [query, setQuery] = useState("");

  const go = (to: string) => {
    palette.close();
    navigate(to);
  };

  const pages: SpotlightActionGroupData = {
    group: t("appShell.palette.groupPages"),
    actions: [...visibleSections(isAdmin()).flatMap((section) => section.items), ...ACCOUNT_NAV].map(
      (leaf) => {
        const Icon = leaf.icon;
        return {
          id: `page:${leaf.to}`,
          label: t(leaf.label),
          leftSection: <Icon size={18} stroke={1.5} />,
          onClick: () => go(leaf.to),
        };
      },
    ),
  };
  const actionsGroup: SpotlightActionGroupData = {
    group: t("appShell.palette.groupActions"),
    actions: [
      { id: "action:new-contract", label: t("appShell.palette.newContract"), leftSection: <IconPlus size={18} stroke={1.5} />, onClick: () => go(newContractPath) },
      { id: "action:import", label: t("appShell.palette.importContract"), leftSection: <IconFileImport size={18} stroke={1.5} />, onClick: () => go(importContractPath) },
    ],
  };
  const [debounced] = useDebouncedValue(query.trim(), 300);
  const search = useQuery({
    queryKey: ["contracts", "palette", debounced],
    queryFn: () => listContracts({ page: 1, pageSize: 8, sort: "name", q: debounced }),
    enabled: debounced.length >= 2,
  });
  const contractsGroup: SpotlightActionGroupData = {
    group: t("appShell.palette.groupContracts"),
    actions: (search.data?.items ?? []).map((contract) => ({
      id: `contract:${contract.id}`,
      label: contract.name,
      description: `${contract.domain.name} / ${contract.system.name}`,
      leftSection: <IconFileText size={18} stroke={1.5} />,
      onClick: () => go(contractPath(contract.id)),
    })),
  };
  // Pages/actions match the folded query on their label; the contracts group is already the server's match.
  const filter: SpotlightFilterFunction = (q, actions) => {
    const folded = foldDiacritics(q.trim());
    return actions.flatMap((entry): SpotlightActions[] => {
      if (!("actions" in entry)) return [entry];
      const group = entry as SpotlightActionGroupData;
      if (group.group === contractsGroup.group) return group.actions.length > 0 ? [group] : [];
      const items = group.actions.filter((a: SpotlightActionData) => foldDiacritics(String(a.label ?? "")).includes(folded));
      return items.length > 0 ? [{ ...group, actions: items }] : [];
    });
  };
  const shortcut = os === "macos" ? "⌘ K" : "Ctrl K";
  return (
    <>
      <UnstyledButton
        className={classes.paletteTrigger}
        visibleFrom="sm"
        aria-label={t("appShell.palette.open")}
        onClick={palette.open}
      >
        <IconSearch size={16} />
        <Text component="span" size="sm" inherit>
          {t("appShell.palette.placeholder")}
        </Text>
        <Kbd size="xs">{shortcut}</Kbd>
      </UnstyledButton>
      <ActionIcon hiddenFrom="sm" size="lg" aria-label={t("appShell.palette.open")} onClick={palette.open}>
        <IconSearch size={18} />
      </ActionIcon>
      <Spotlight
        store={paletteStore}
        shortcut="mod + K"
        query={query}
        onQueryChange={setQuery}
        actions={[pages, actionsGroup, contractsGroup]}
        filter={filter}
        nothingFound={debounced.length < 2 && query.trim().length > 0 ? t("appShell.palette.searchHint") : t("appShell.palette.nothingFound")}
        scrollable
        maxHeight={420}
        searchProps={{
          leftSection: <IconSearch size={18} stroke={1.5} />,
          placeholder: t("appShell.palette.placeholder"),
        }}
      />
    </>
  );
}
