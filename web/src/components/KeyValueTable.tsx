import { Table, Text } from "@mantine/core";
import type { KeyValue } from "../api/versions";

/** Two-column rows for the model's stringified maps (custom properties, server details, scopes). */
export default function KeyValueTable({ rows, ariaLabel }: { rows: readonly KeyValue[]; ariaLabel: string }) {
  if (rows.length === 0) return null;
  return (
    <Table fz="sm" aria-label={ariaLabel} withRowBorders={false}>
      <Table.Tbody>
        {rows.map((r, i) => (
          <Table.Tr key={`${r.key}-${i}`}>
            <Table.Td w="35%" fw={500} style={{ verticalAlign: "top" }}>
              {r.key}
            </Table.Td>
            <Table.Td>
              <Text size="sm" style={{ wordBreak: "break-word" }}>
                {r.value}
              </Text>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}
