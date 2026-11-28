import { useId, type ReactNode } from "react";
import { Badge, Button, Group, Paper } from "@mantine/core";
import { IconChevronDown, IconFilter } from "@tabler/icons-react";
import { useTranslation } from "react-i18next";
import { isBoolean, useStoredState } from "../hooks/useStoredState";
import classes from "../theme.module.css";

/**
 * The collapsible filter drawer above every list table: collapsed by default, open state
 * persisted per view, with an active-filter-count badge on the toggle.
 */
export default function FilterPanel({
  activeFilterCount,
  storageKey,
  children,
  aside,
  trailing,
}: {
  activeFilterCount: number;
  storageKey: string;
  children: ReactNode;
  /** Rendered in the header row beside the toggle — reachable with the panel collapsed
   *  (the LensPicker's slot). */
  aside?: ReactNode;
  /** Rendered at the END of the header row, pushed right (the Kind pills' slot, v1.20.0). */
  trailing?: ReactNode;
}) {
  const { t } = useTranslation();
  const [open, setOpen] = useStoredState(`${storageKey}.filtersOpen`, false, isBoolean);
  const panelId = useId();
  return (
    <div>
      <Group gap="xs" mb={open ? "xs" : 0} wrap="wrap">
        <Button
          variant="default"
          size="xs"
          onClick={() => setOpen(!open)}
          aria-expanded={open}
          aria-controls={open ? panelId : undefined}
          leftSection={<IconFilter size={16} />}
          rightSection={
            <Group gap={6} wrap="nowrap" component="span">
              {activeFilterCount > 0 && (
                <Badge size="sm" circle variant="filled">
                  {activeFilterCount}
                </Badge>
              )}
              <IconChevronDown size={16} className={classes.filterChevron} data-open={open || undefined} aria-hidden />
            </Group>
          }
        >
          {t("common.filter.title")}
        </Button>
        {aside}
        {trailing && <Group ml="auto">{trailing}</Group>}
      </Group>
      {open && (
        <Paper id={panelId} withBorder radius="md" p="sm" className={classes.filterFields}>
          {children}
        </Paper>
      )}
    </div>
  );
}
