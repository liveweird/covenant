import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { Link as RouterLink, useNavigate, useParams } from "react-router-dom";
import { Alert, Anchor, Box, Code, Stack, Table, Text } from "@mantine/core";
import { IconPlugConnectedX } from "@tabler/icons-react";
import { useQuery } from "@tanstack/react-query";
import { listToadieLinkMatches } from "../api/toadie";
import ContractNameLink from "../components/ContractNameLink";
import EmptyState from "../components/EmptyState";
import LifecyclePill from "../components/LifecyclePill";
import LoadingBlock from "../components/LoadingBlock";
import PageHeader from "../components/PageHeader";
import TypeBadge from "../components/TypeBadge";
import { contractPath, contractsPath, versionPath } from "../utils/contractLinks";
import { CONTENT_MAX_WIDTH } from "../utils/layout";
import { loadErrorMessage } from "../utils/saveError";

/**
 * `/toadie/:blueprint/:identifier` — the way IN from Toadie. Toadie renders this URL on its own
 * `api`/`dataset` entity pages (a calculated blueprint property over the entity's identifier), and
 * this page resolves it to whatever Covenant contract(s) cover that entity right now. The URL shape
 * is a stable cross-app contract (pinned on the route in `App.tsx` and documented in
 * `.claude/docs/toadie-integration.md`); Covenant owns the link, so Toadie stores nothing.
 *
 * One match redirects with `replace`, so the browser Back button returns to Toadie rather than
 * bouncing through this page. Several matches (Covenant may hold more than one connection over the
 * same blueprint) list themselves with the connection that produced each. None is a plain empty
 * state: "nothing linked yet" is a real answer here, never an error and never a 404.
 *
 * Both params arrive ALREADY DECODED — React Router decodes path params, so a second
 * `decodeURIComponent` would be a double decode. An identifier may contain `/` (encoded `%2F`),
 * `+`, `:`, `@`, `=` and unicode letters, so it goes to the server as a query VALUE, never as a
 * path segment.
 */
export default function ToadieLinkResolver() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { blueprint, identifier } = useParams<{ blueprint: string; identifier: string }>();

  const matches = useQuery({
    queryKey: ["toadie", "resolve", blueprint, identifier],
    queryFn: () => listToadieLinkMatches({ blueprint: blueprint ?? "", identifier: identifier ?? "", page: 1, pageSize: 100 }),
    enabled: !!blueprint && !!identifier,
  });

  const only = matches.data?.total === 1 ? matches.data.items[0] : undefined;
  useEffect(() => {
    if (only) navigate(contractPath(only.contractId), { replace: true });
  }, [only, navigate]);

  const entity = (
    <>
      {t("toadie.resolve.entity", { blueprint })} <Code>{identifier}</Code>
    </>
  );

  return (
    <Stack gap="md">
      <PageHeader title={t("toadie.resolve.title")} description={entity} backTo={{ to: contractsPath, label: t("toadie.resolve.back") }} />
      {matches.isError && (
        <Alert color="red" variant="light">
          {loadErrorMessage(matches.error, t)}
        </Alert>
      )}
      {/* The redirect fires in an effect, so the single-match render is still a load state. */}
      {(matches.isLoading || only) && <LoadingBlock />}
      {!matches.isLoading && !matches.isError && matches.data?.total === 0 && (
        <EmptyState icon={IconPlugConnectedX} label={t("toadie.resolve.empty", { blueprint, identifier })} />
      )}
      {!only && !!matches.data?.total && matches.data.total > 1 && (
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            {t("toadie.resolve.several", { count: matches.data.total })}
          </Text>
          <Box maw={CONTENT_MAX_WIDTH}>
            <Table>
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>{t("toadie.resolve.column.contract")}</Table.Th>
                  <Table.Th>{t("toadie.resolve.column.type")}</Table.Th>
                  <Table.Th>{t("toadie.resolve.column.system")}</Table.Th>
                  <Table.Th>{t("toadie.resolve.column.latestVersion")}</Table.Th>
                  <Table.Th>{t("toadie.resolve.column.connection")}</Table.Th>
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {matches.data.items.map((match) => (
                  <Table.Tr key={match.linkId}>
                    <Table.Td>
                      <ContractNameLink id={match.contractId} name={match.contractName} />
                    </Table.Td>
                    <Table.Td>
                      <TypeBadge type={match.type} />
                    </Table.Td>
                    <Table.Td>{match.system.name}</Table.Td>
                    <Table.Td>
                      {match.latestVersion ? (
                        <Anchor component={RouterLink} to={versionPath(match.contractId, match.latestVersion.id, "reader")} size="sm">
                          {match.latestVersion.version} <LifecyclePill lifecycle={match.latestVersion.lifecycle} size="xs" />
                        </Anchor>
                      ) : (
                        <Text size="sm" c="dimmed">
                          {t("toadie.resolve.noVersion")}
                        </Text>
                      )}
                    </Table.Td>
                    <Table.Td>{match.connection.name}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </Box>
        </Stack>
      )}
    </Stack>
  );
}
