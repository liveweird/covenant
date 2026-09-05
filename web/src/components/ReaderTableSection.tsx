import { Table } from "@mantine/core";
import type { ReactNode } from "react";
import ReaderSection from "./ReaderSection";

/** A reader section whose body is one borderless table of rows — the team / roles / SLA / support shape. */
export default function ReaderTableSection<T>({
  id,
  title,
  pointer,
  rows,
  rowKey,
  cells,
  lead,
}: {
  id: string;
  title: string;
  pointer: string;
  rows: readonly T[];
  rowKey: (row: T, index: number) => string;
  /** One cell per column; `undefined`/`null` cells still render (an empty `<td>` keeps the grid). */
  cells: (row: T) => ReactNode[];
  /** Optional content above the table (the SLA default element). */
  lead?: ReactNode;
}) {
  return (
    <ReaderSection id={id} title={title} pointer={pointer}>
      {lead}
      <Table fz="sm" withRowBorders={false} aria-label={title}>
        <Table.Tbody>
          {rows.map((row, index) => (
            <Table.Tr key={rowKey(row, index)}>
              {cells(row).map((cell, i) => (
                <Table.Td key={i} fw={i === 0 ? 500 : undefined}>
                  {cell}
                </Table.Td>
              ))}
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </ReaderSection>
  );
}
