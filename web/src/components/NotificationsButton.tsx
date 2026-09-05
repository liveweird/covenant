import { useState } from "react";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { ActionIcon, Alert, Box, Button, Drawer, Group, Indicator, Pagination, Stack, Text, ThemeIcon, Tooltip } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import {
  IconAlertTriangle,
  IconBell,
  IconBellOff,
  IconCheck,
  IconChecks,
  IconCloudDownload,
  IconExternalLink,
  IconEyeOff,
  IconFileImport,
  IconFilePlus,
  IconLink,
  IconPencil,
  IconTrash,
  IconTransfer,
  IconUsers,
} from "@tabler/icons-react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { deleteNotification, listNotifications, markAllNotificationsSeen, markNotificationSeen, markNotificationUnseen, type NotificationItem } from "../api/notifications";
import EmptyState from "./EmptyState";
import LoadingBlock from "./LoadingBlock";
import { LIFECYCLES } from "../utils/lifecycle";
import { relativeTimeAgo } from "../utils/relativeTime";
import { loadErrorMessage } from "../utils/saveError";

type Kind = NotificationItem["type"];

/** Per-kind row icon + accent; an unknown kind (a newer server) falls back to the plain bell. */
const TYPE_META: Record<Kind, { icon: typeof IconBell; color: string }> = {
  CONTRACT_UPDATED: { icon: IconPencil, color: "gray" },
  CONTRACT_OWNER_CHANGED: { icon: IconUsers, color: "gray" },
  CONTRACT_DELETED: { icon: IconTrash, color: "red" },
  VERSION_CREATED: { icon: IconFilePlus, color: "covenant" },
  VERSION_CONTENT_UPDATED: { icon: IconPencil, color: "gray" },
  VERSION_TRANSITIONED: { icon: IconTransfer, color: "teal" },
  VERSION_DELETED: { icon: IconTrash, color: "red" },
  VERSION_SYNCED: { icon: IconCloudDownload, color: "gray" },
  VERSION_SOURCE_CHANGED: { icon: IconLink, color: "gray" },
  VERSION_IMPORTED: { icon: IconFileImport, color: "covenant" },
  VERSION_BREAKING_STORED: { icon: IconAlertTriangle, color: "orange" },
};

const KNOWN_KINDS = new Set<string>(Object.keys(TYPE_META));

/** The lifecycle words in the viewer's language; a value this build does not know renders raw. */
function lifecycleWord(value: string | undefined, t: TFunction): string | undefined {
  const known = LIFECYCLES.find((l) => l === value);
  return known ? t(`versions.lifecycle.${known}`) : value;
}

/** One localized sentence per notification, interpolating the structural params the server stored. */
function describeNotification(n: NotificationItem, t: TFunction): string {
  if (!KNOWN_KINDS.has(n.type)) return n.type; // forward-compat: an unknown kind shows its raw name
  const params = { ...n.params, from: lifecycleWord(n.params.from, t), to: lifecycleWord(n.params.to, t) };
  return t(`notifications.event.${n.type}`, params);
}

// Poll the bell so notifications minted elsewhere show up without a manual refresh;
// `refetchIntervalInBackground` defaults to false, so polling pauses while the tab is hidden.
const UNREAD_REFETCH_MS = 30_000;
const PAGE_SIZE = 50;

/**
 * The header bell (Lettuce's NotificationsButton, ported): an unread badge fed by a
 * pageSize-1 unseen query, and a right-hand Drawer listing the caller's notifications newest
 * first with seen/unseen/open/delete per row and "Mark all as seen". Opening a link marks the
 * row seen (acting on it) and navigates. Deliberately no success toasts (a documented skip).
 */
export default function NotificationsButton() {
  const { t, i18n } = useTranslation();
  const [opened, { open, close }] = useDisclosure(false);
  const [page, setPage] = useState(1);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const unreadQuery = useQuery({
    queryKey: ["notifications", "unread"],
    queryFn: () => listNotifications({ page: 1, pageSize: 1, wasSeen: false }),
    refetchInterval: UNREAD_REFETCH_MS,
  });
  const unreadCount = unreadQuery.data?.total ?? 0;

  const listQuery = useQuery({
    queryKey: ["notifications", "list", page],
    queryFn: () => listNotifications({ page, pageSize: PAGE_SIZE, sort: "-timestamp" }),
    enabled: opened,
    refetchInterval: UNREAD_REFETCH_MS,
    placeholderData: keepPreviousData,
  });
  const total = listQuery.data?.total ?? 0;
  const items = listQuery.data?.items ?? [];
  // An emptied last page (a deletion, or the poll seeing one) steps back — adjusted during render.
  if (listQuery.data && items.length === 0 && page > 1) setPage(page - 1);

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ["notifications"] });
  const markSeen = useMutation({ mutationFn: (id: number) => markNotificationSeen(id), onSuccess: invalidate });
  const markUnseen = useMutation({ mutationFn: (id: number) => markNotificationUnseen(id), onSuccess: invalidate });
  const markAllSeen = useMutation({ mutationFn: () => markAllNotificationsSeen(), onSuccess: invalidate });
  const remove = useMutation({ mutationFn: (id: number) => deleteNotification(id), onSuccess: invalidate });

  function goTo(n: NotificationItem) {
    if (!n.link) return;
    if (!n.wasSeen) markSeen.mutate(n.id); // acting on it = seen; fire-and-forget
    close();
    navigate(n.link);
  }

  return (
    <>
      <Indicator inline size={18} offset={4} color="red" label={unreadCount > 99 ? "99+" : unreadCount} disabled={unreadCount === 0}>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="lg"
          onClick={() => {
            setPage(1);
            open();
          }}
          aria-label={`${t("notifications.title")} (${t("notifications.unread", { count: unreadCount })})`}
        >
          <IconBell size={18} />
        </ActionIcon>
      </Indicator>
      <Drawer.Root opened={opened} onClose={close} position="right" size={440}>
        <Drawer.Overlay />
        <Drawer.Content>
          <Drawer.Header>
            <Drawer.Title>{t("notifications.title")}</Drawer.Title>
            <Group gap="sm">
              {unreadCount > 0 && (
                <Button size="xs" variant="light" leftSection={<IconChecks size={14} />} onClick={() => markAllSeen.mutate()} loading={markAllSeen.isPending}>
                  {t("notifications.markAllSeen")}
                </Button>
              )}
              <Drawer.CloseButton aria-label={t("common.action.close")} />
            </Group>
          </Drawer.Header>
          <Drawer.Body>
            {listQuery.isLoading ? (
              <LoadingBlock />
            ) : listQuery.isError ? (
              <Alert color="red" variant="light" title={t("notifications.loadError")}>
                {loadErrorMessage(listQuery.error, t)}
              </Alert>
            ) : items.length === 0 ? (
              <EmptyState icon={IconBellOff} label={t("notifications.empty")} />
            ) : (
              <Box component="ul" m={0} p={0} style={{ listStyle: "none" }}>
                {items.map((n, i) => {
                  const meta = TYPE_META[n.type] ?? { icon: IconBell, color: "gray" };
                  const TypeIcon = meta.icon;
                  return (
                    <Box key={n.id} component="li" py="sm" px={4} style={{ borderTop: i > 0 ? "1px solid var(--mantine-color-default-border)" : undefined }}>
                      <Group align="flex-start" wrap="nowrap" gap="sm">
                        <Indicator size={9} offset={3} disabled={n.wasSeen}>
                          <ThemeIcon variant="light" color={meta.color} radius="xl" size="lg">
                            <TypeIcon size={16} />
                          </ThemeIcon>
                        </Indicator>
                        <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                          <Text size="sm" fw={n.wasSeen ? 400 : 600} c={n.wasSeen ? "dimmed" : undefined}>
                            {describeNotification(n, t)}
                          </Text>
                          <Text size="xs" c="dimmed">
                            {relativeTimeAgo(n.timestamp, i18n.language)}
                          </Text>
                        </Stack>
                        <Group gap={4} wrap="nowrap">
                          {n.wasSeen ? (
                            <Tooltip label={t("notifications.markUnseen")}>
                              <ActionIcon onClick={() => markUnseen.mutate(n.id)} aria-label={t("notifications.markUnseenAria", { id: n.id })}>
                                <IconEyeOff size={16} />
                              </ActionIcon>
                            </Tooltip>
                          ) : (
                            <Tooltip label={t("notifications.markSeen")}>
                              <ActionIcon onClick={() => markSeen.mutate(n.id)} aria-label={t("notifications.markSeenAria", { id: n.id })}>
                                <IconCheck size={16} />
                              </ActionIcon>
                            </Tooltip>
                          )}
                          {n.link && (
                            <Tooltip label={t("notifications.goTo")}>
                              <ActionIcon onClick={() => goTo(n)} aria-label={t("notifications.goToAria", { id: n.id })}>
                                <IconExternalLink size={16} />
                              </ActionIcon>
                            </Tooltip>
                          )}
                          <Tooltip label={t("notifications.delete")}>
                            <ActionIcon color="red" onClick={() => remove.mutate(n.id)} aria-label={t("notifications.deleteAria", { id: n.id })}>
                              <IconTrash size={16} />
                            </ActionIcon>
                          </Tooltip>
                        </Group>
                      </Group>
                    </Box>
                  );
                })}
              </Box>
            )}
            {total > PAGE_SIZE && (
              <Group justify="center" mt="sm">
                <Pagination size="sm" value={page} onChange={setPage} total={Math.ceil(total / PAGE_SIZE)} />
              </Group>
            )}
          </Drawer.Body>
        </Drawer.Content>
      </Drawer.Root>
    </>
  );
}
