import { Table, UnstyledButton } from "@mantine/core";
import { IconArrowDown, IconArrowUp, IconArrowsSort } from "@tabler/icons-react";
import classes from "../theme.module.css";

export type SortDir = "asc" | "desc";

/**
 * A sortable column header cycling the list sort (pair with usePagedSort's toggleSort).
 * Renders its own `Table.Th` so the sort state also lands as `aria-sort` on the header
 * cell — the icon alone is mouse-eyes-only.
 */
export default function SortHeader<F extends string>({
  field,
  label,
  activeField,
  activeDir,
  onToggle,
  width,
}: {
  field: F;
  label: string;
  activeField: F;
  activeDir: SortDir;
  onToggle: (field: F) => void;
  /** A fixed column width (px) for `layout="fixed"` tables — omit on the one wide column. */
  width?: number;
}) {
  const isActive = activeField === field;
  const Icon = !isActive ? IconArrowsSort : activeDir === "asc" ? IconArrowUp : IconArrowDown;
  return (
    <Table.Th aria-sort={!isActive ? "none" : activeDir === "asc" ? "ascending" : "descending"} w={width}>
      <UnstyledButton
        onClick={() => onToggle(field)}
        className={classes.sortHeaderButton}
      >
        <span>{label}</span>
        <Icon size={14} stroke={1.5} opacity={isActive ? 1 : 0.4} aria-hidden />
      </UnstyledButton>
    </Table.Th>
  );
}
