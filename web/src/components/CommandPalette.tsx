import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { ActionIcon, Kbd, Text, UnstyledButton } from "@mantine/core";
import { useOs } from "@mantine/hooks";
import { Spotlight, type SpotlightActionGroupData } from "@mantine/spotlight";
import { IconSearch } from "@tabler/icons-react";
import { isAdmin } from "../api/session";
import { ACCOUNT_NAV, visibleSections } from "../utils/navigation";
import { palette, paletteStore } from "../utils/commandPalette";
import classes from "../theme.module.css";

/**
 * The command palette: ⌘K / Ctrl K, or the search-looking trigger in the header. One group
 * today — every page the session may see (the same nav model as the sidebar plus the account
 * leaves); the contract actions and the server-side contract search join it with the contracts
 * feature. Renders BOTH the trigger and the Spotlight, mounted once in the shell header. No
 * `highlightQuery`: it splits a label into `<mark>` + text fragments, and tests/e2e locate
 * results by their full name.
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
        actions={[pages]}
        nothingFound={t("appShell.palette.nothingFound")}
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
