// Notifications API — the recipient-scoped list and read-state actions (Lettuce's, ported).
// Notifications are minted server-side for a contract's followers; there is no create call.

import { buildQuery, jsonRequest, voidRequest } from "./http";
import type { paths } from "./schema";

export type NotificationPage = paths["/api/v1/notifications"]["get"]["responses"]["200"]["content"]["application/json"];
export type NotificationItem = NotificationPage["items"][number];

type NotificationListQuery = { page: number; pageSize: number; sort?: string; wasSeen?: boolean };

export async function listNotifications(q: NotificationListQuery): Promise<NotificationPage> {
  const params = buildQuery({ page: q.page, pageSize: q.pageSize, sort: q.sort, wasSeen: q.wasSeen });
  return jsonRequest<NotificationPage>(`/api/v1/notifications?${params}`);
}

export async function markNotificationSeen(id: number): Promise<void> {
  await voidRequest(`/api/v1/notifications/${id}/seen`, { method: "POST" });
}

export async function markNotificationUnseen(id: number): Promise<void> {
  await voidRequest(`/api/v1/notifications/${id}/unseen`, { method: "POST" });
}

export async function markAllNotificationsSeen(): Promise<void> {
  await voidRequest("/api/v1/notifications/seen-all", { method: "POST" });
}

export async function deleteNotification(id: number): Promise<void> {
  await voidRequest(`/api/v1/notifications/${id}`, { method: "DELETE" });
}
