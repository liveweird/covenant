import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Select, type ComboboxItemGroup } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useQuery } from "@tanstack/react-query";
import { getUserId, isAdmin } from "../api/session";
import { listTeams } from "../api/teams";
import { getUser, listUsers } from "../api/users";

/**
 * The owner picker — ONE Select over the server's team-XOR-user rule (`TEAM:<id>` / `USER:<id>`
 * values, see utils/contractForm.ts). What it offers follows the assignability rule: a regular
 * user may hand a contract to a team they belong to or keep it personally; an ADMIN may pick any
 * team or any user (users searched server-side, debounced). A current owner outside the loaded
 * options (an edit of someone else's contract) is injected so the Select can show it.
 */
export default function OwnerSelect({
  value,
  onChange,
  error,
  disabled = false,
  description,
  current,
}: {
  value: string | null;
  onChange: (value: string | null) => void;
  error?: string;
  disabled?: boolean;
  description?: string;
  /** The stored owner, so its label renders even when the option lists do not contain it. */
  current?: { value: string; label: string } | null;
}) {
  const { t } = useTranslation();
  const admin = isAdmin();
  const me = getUserId();
  const [search, setSearch] = useState("");
  const [debounced] = useDebouncedValue(search.trim(), 300);

  const teams = useQuery({
    queryKey: ["teams", "picker", admin ? "all" : me],
    queryFn: () => listTeams({ page: 1, pageSize: 100, sort: "name", memberId: admin ? undefined : (me ?? undefined) }),
  });
  const users = useQuery({
    queryKey: ["users", "picker", debounced],
    queryFn: () => listUsers({ page: 1, pageSize: 50, sort: "name", name: debounced || undefined }),
    enabled: admin,
  });
  const self = useQuery({ queryKey: ["user", me], queryFn: () => getUser(me as number), enabled: !admin && me != null });

  const teamItems = (teams.data?.items ?? []).map((team) => ({ value: `TEAM:${team.id}`, label: team.name }));
  const userItems = admin
    ? (users.data?.items ?? []).map((user) => ({ value: `USER:${user.id}`, label: `${user.name} (${user.email})` }))
    : self.data
      ? [{ value: `USER:${self.data.id}`, label: t("contracts.owner.me", { name: self.data.name }) }]
      : [];
  const known = new Set([...teamItems, ...userItems].map((item) => item.value));
  const extra = current && !known.has(current.value) ? [{ value: current.value, label: current.label }] : [];
  const data: ComboboxItemGroup<{ value: string; label: string }>[] = [
    { group: t("contracts.owner.teams"), items: teamItems },
    { group: t("contracts.owner.users"), items: [...userItems, ...extra.filter((e) => e.value.startsWith("USER:"))] },
  ];
  const teamExtra = extra.filter((e) => e.value.startsWith("TEAM:"));
  if (teamExtra.length > 0) data[0] = { ...data[0], items: [...teamItems, ...teamExtra] };

  return (
    <Select
      label={t("contracts.field.owner")}
      description={description}
      placeholder={t("contracts.owner.pick")}
      data={data}
      value={value}
      onChange={onChange}
      error={error}
      disabled={disabled}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      nothingFoundMessage={t("contracts.owner.nothingFound")}
      // Admin searches hit the server (the users list); the local fold would hide server matches
      // whose label differs from the query only by what the server already matched.
      filter={admin ? ({ options }) => options : undefined}
      clearable
      allowDeselect={false}
    />
  );
}
