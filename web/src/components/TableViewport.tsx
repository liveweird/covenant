import type { ReactNode } from "react";
import { Table } from "@mantine/core";
import classes from "./TableViewport.module.css";

/**
 * A named, keyboard-scrollable viewport for data tables that retain readable
 * intrinsic column widths on narrow screens.
 */
export default function TableViewport({
  label,
  minWidth,
  empty,
  children,
}: {
  label: string;
  minWidth: number;
  empty?: boolean;
  children: ReactNode;
}) {
  return (
    <Table.ScrollContainer
      type="native"
      minWidth={minWidth}
      role="region"
      aria-label={label}
      tabIndex={0}
      className={classes.viewport}
      data-table-viewport
      data-empty={empty || undefined}
    >
      {children}
    </Table.ScrollContainer>
  );
}
