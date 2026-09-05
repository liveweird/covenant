import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink } from "react-router-dom";
import { ActionIcon, Alert, Anchor, Box, Button, Group, Stack, Text, Tooltip } from "@mantine/core";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { IconChevronDown, IconChevronRight, IconChevronsDown, IconChevronsUp, IconFileImport, IconFolders, IconPlus, IconServer2, IconSitemap } from "@tabler/icons-react";
import { getContractFacets, getContractTree, type TreeContract, type TreeDomain, type TreeSystem } from "../api/contracts";
import CheckSummaryBadges from "../components/CheckSummaryBadges";
import ContractFilterControls from "../components/ContractFilterControls";
import ContractNameLink from "../components/ContractNameLink";
import EmptyState from "../components/EmptyState";
import FilterPanel from "../components/FilterPanel";
import LifecyclePill from "../components/LifecyclePill";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import TypeBadge from "../components/TypeBadge";
import { useContractFilterState } from "../hooks/useContractFilterState";
import { importContractPath, newContractPath, versionPath } from "../utils/contractLinks";
import { loadErrorMessage } from "../utils/saveError";
import classes from "../theme.module.css";

const SETTINGS_KEY = "hierarchy";

function ContractRow({ contract }: { contract: TreeContract }) {
  const { t } = useTranslation();
  return (
    <Group gap="xs" wrap="nowrap" py={3} px={4} className={classes.treeRow} role="treeitem" aria-level={3}>
      <Box w={18} style={{ flexShrink: 0 }} />
      <TypeBadge type={contract.type} size="xs" />
      <ContractNameLink id={contract.id} name={contract.name} />
      {contract.latestVersion ? (
        <Group gap={6} wrap="nowrap">
          <Anchor
            component={RouterLink}
            to={versionPath(contract.id, contract.latestVersion.id, "reader")}
            size="xs"
            ff="monospace"
            aria-label={t("versions.readAria", { version: contract.latestVersion.version, name: contract.name })}
          >
            {contract.latestVersion.version}
          </Anchor>
          <LifecyclePill lifecycle={contract.latestVersion.lifecycle} size="xs" />
          <CheckSummaryBadges errors={contract.latestVersion.checkErrors} warnings={contract.latestVersion.checkWarnings} complete={contract.latestVersion.checkComplete} />
        </Group>
      ) : (
        <Text size="xs" c="dimmed">
          {t("contracts.noVersions")}
        </Text>
      )}
      <Text size="xs" c="dimmed" ml="auto" truncate style={{ flexShrink: 0 }}>
        {contract.owner.name}
      </Text>
    </Group>
  );
}

function Branch({
  id,
  label,
  icon: Icon,
  count,
  level,
  collapsed,
  onToggle,
  children,
}: {
  id: string;
  label: string;
  icon: typeof IconFolders;
  count: number;
  level: number;
  collapsed: ReadonlySet<string>;
  onToggle: (key: string) => void;
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  const isCollapsed = collapsed.has(id);
  return (
    <Box role="treeitem" aria-level={level} aria-expanded={!isCollapsed}>
      <Group gap="xs" wrap="nowrap" py={3} px={4} className={classes.treeRow}>
        <ActionIcon size="xs" aria-label={t("hierarchy.toggleAria", { name: label })} aria-expanded={!isCollapsed} onClick={() => onToggle(id)}>
          {isCollapsed ? <IconChevronRight size={14} /> : <IconChevronDown size={14} />}
        </ActionIcon>
        <Icon size={16} stroke={1.5} color="var(--mantine-color-dimmed)" />
        <Text size="sm" fw={level === 1 ? 600 : 500}>
          {label}
        </Text>
        <Text size="xs" c="dimmed">
          {t("hierarchy.contractCount", { count })}
        </Text>
      </Group>
      {!isCollapsed && (
        <Box pl={22} ml={13} className={classes.treeBranch} role="group">
          {children}
        </Box>
      )}
    </Box>
  );
}

const systemKey = (s: TreeSystem) => `s${s.id}`;
const domainKey = (d: TreeDomain) => `d${d.id}`;
const contractsIn = (d: TreeDomain) => d.systems.reduce((n, s) => n + s.contracts.length, 0);

/**
 * The catalog's home (`/`): Domain → System → Contract as a collapsible tree, the same filter
 * set as the list (a filter narrows the contracts; the domain/system spine stays so an empty
 * branch reads as "nothing here", not "nothing exists"). Registry-scale, unpaged by design.
 */
export default function Hierarchy() {
  const { t } = useTranslation();
  const filters = useContractFilterState(SETTINGS_KEY);
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set());
  // The facet counts ride the same filters; a stale count while the next one loads beats a flicker.
  const facets = useQuery({
    queryKey: ["contracts", "facets", filters.values],
    queryFn: () => getContractFacets(filters.values),
    placeholderData: keepPreviousData,
  });
  const { data, isPending, isError, error } = useQuery({
    queryKey: ["contracts", "tree", filters.values],
    queryFn: () => getContractTree(filters.values),
    placeholderData: keepPreviousData,
  });

  function toggle(key: string) {
    setCollapsed((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }
  const allKeys = (data?.domains ?? []).flatMap((d) => [domainKey(d), ...d.systems.map(systemKey)]);

  return (
    <Stack gap="md">
      <PageHeader
        title={t("hierarchy.title")}
        description={t("hierarchy.intro")}
        actions={
          <>
            <Button component={RouterLink} to={importContractPath} variant="default" leftSection={<IconFileImport size={16} />}>
              {t("contracts.import")}
            </Button>
            <Button component={RouterLink} to={newContractPath} leftSection={<IconPlus size={16} />}>
              {t("contracts.newContract")}
            </Button>
          </>
        }
      />
      <FilterPanel
        activeFilterCount={filters.activeCount}
        storageKey={SETTINGS_KEY}
        aside={
          <Group gap={4}>
            <Tooltip label={t("hierarchy.expandAll")}>
              <ActionIcon variant="default" size="md" aria-label={t("hierarchy.expandAll")} onClick={() => setCollapsed(new Set())}>
                <IconChevronsDown size={16} />
              </ActionIcon>
            </Tooltip>
            <Tooltip label={t("hierarchy.collapseAll")}>
              <ActionIcon variant="default" size="md" aria-label={t("hierarchy.collapseAll")} onClick={() => setCollapsed(new Set(allKeys))}>
                <IconChevronsUp size={16} />
              </ActionIcon>
            </Tooltip>
          </Group>
        }
      >
        <ContractFilterControls filters={filters} facets={facets.data ?? null} />
      </FilterPanel>
      {isError && (
        <Alert color="red" variant="light" title={t("hierarchy.loadFailed")}>
          {loadErrorMessage(error, t)}
        </Alert>
      )}
      {isPending && !data ? (
        <LoadingBlock />
      ) : data && data.domains.length > 0 ? (
        <Box role="tree" aria-label={t("hierarchy.treeAria")}>
          {data.domains.map((domain) => (
            <Branch key={domain.id} id={domainKey(domain)} label={domain.name} icon={IconFolders} count={contractsIn(domain)} level={1} collapsed={collapsed} onToggle={toggle}>
              {domain.systems.length === 0 && (
                <Text size="xs" c="dimmed" fs="italic" py={3} px={4}>
                  {t("hierarchy.noSystems")}
                </Text>
              )}
              {domain.systems.map((system) => (
                <Branch key={system.id} id={systemKey(system)} label={system.name} icon={IconServer2} count={system.contracts.length} level={2} collapsed={collapsed} onToggle={toggle}>
                  {system.contracts.length === 0 && (
                    <Text size="xs" c="dimmed" fs="italic" py={3} px={4}>
                      {t("hierarchy.noContracts")}
                    </Text>
                  )}
                  {system.contracts.map((contract) => (
                    <ContractRow key={contract.id} contract={contract} />
                  ))}
                </Branch>
              ))}
            </Branch>
          ))}
        </Box>
      ) : !isError ? (
        <EmptyState icon={IconSitemap} label={t("hierarchy.empty")} />
      ) : null}
    </Stack>
  );
}
