import type { ParseKeys } from "i18next";
import { IconAlertTriangle, IconFileText, IconFolders, IconHistory, IconKey, IconPlugConnected, IconServer2, IconSitemap, IconToggleLeft, IconUsers, IconUsersGroup, IconWand, type Icon } from "@tabler/icons-react";
import { contractsPath, errorsPath, hierarchyPath, inferContractPath } from "./contractLinks";
import { teamsPath } from "./teamLinks";
import { environmentsPath } from "./environmentLinks";

export type NavLeaf = {
  to: string;
  /** An i18n key, resolved with t() at render time. */
  label: ParseKeys;
  icon: Icon;
  /** When set, the leaf renders only for ADMIN sessions. */
  adminOnly?: boolean;
};

/** A labelled, always-open block of leaves — a section, never a collapsible group. */
export type NavSection = {
  label: ParseKeys;
  items: ReadonlyArray<NavLeaf>;
};

/**
 * The navigation model, shared by the sidebar and the command palette. Sections are labelled,
 * always-open blocks (never collapsible groups): every leaf is always in the DOM, so tests and
 * deep links address the links directly, and a static label costs less vertical space than a
 * toggle. Catalog and Registries leaves are visible to everyone (non-admins get read-only
 * registry lists); the Administration section is the ADMIN management surface.
 */
const NAV_SECTIONS: ReadonlyArray<NavSection> = [
  {
    label: "appShell.section.catalog",
    items: [
      { to: hierarchyPath, label: "appShell.nav.hierarchy", icon: IconSitemap },
      { to: contractsPath, label: "appShell.nav.contracts", icon: IconFileText },
      { to: inferContractPath, label: "appShell.nav.infer", icon: IconWand },
      { to: errorsPath, label: "appShell.nav.errors", icon: IconAlertTriangle },
    ],
  },
  {
    label: "appShell.section.registries",
    items: [
      { to: "/domains", label: "appShell.nav.domains", icon: IconFolders },
      { to: "/systems", label: "appShell.nav.systems", icon: IconServer2 },
      { to: environmentsPath, label: "appShell.nav.environments", icon: IconPlugConnected },
      { to: teamsPath, label: "appShell.nav.teams", icon: IconUsersGroup },
    ],
  },
  {
    label: "appShell.section.administration",
    items: [
      { to: "/users", label: "appShell.nav.users", icon: IconUsers, adminOnly: true },
      { to: "/feature-flags", label: "appShell.nav.featureFlags", icon: IconToggleLeft, adminOnly: true },
    ],
  },
];

/** Account-scoped leaves: the header user menu and the palette render them, the sidebar never. */
export const ACCOUNT_NAV: ReadonlyArray<NavLeaf> = [
  { to: "/change-password", label: "appShell.nav.changePassword", icon: IconKey },
  { to: "/changelog", label: "appShell.nav.changelog", icon: IconHistory },
];

/** The sections a session may see: admin-only leaves filtered, empty sections dropped. */
export function visibleSections(admin: boolean): NavSection[] {
  return NAV_SECTIONS.flatMap((section) => {
    const items = section.items.filter((leaf) => !leaf.adminOnly || admin);
    return items.length > 0 ? [{ ...section, items }] : [];
  });
}

/** Longest-matching-prefix active-link resolution — "/" only matches exactly. */
export function activeNavPath(pathname: string, leaves: ReadonlyArray<NavLeaf>): string | null {
  const matches = (to: string) =>
    to === "/" ? pathname === "/" : pathname === to || pathname.startsWith(`${to}/`);
  return (
    leaves
      .map((leaf) => leaf.to)
      .filter(matches)
      .sort((a, b) => b.length - a.length)[0] ?? null
  );
}
