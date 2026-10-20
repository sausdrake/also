import api from './client';
import { parseNotification, type UserNotification } from '../domain/notification';

/**
 * FE-NOTIF-01: REST side of the notifications inbox (backend NotificationController, mounted at both
 * /api/notifications and /api/v1/notifications; the shared /api/v1 client is used). Every call acts on
 * the caller's own notifications - the user comes from the token. A malformed body is an error, never
 * an empty inbox or a zero unread count.
 */
export class MalformedNotificationResponse extends Error {
  constructor(what: string) {
    super(`Malformed notifications response: ${what}`);
    this.name = 'MalformedNotificationResponse';
  }
}

export const NOTIFICATIONS_PAGE_SIZE = 20;

/** GET /notifications?page&size - a Spring Page; only `content` is used. */
export async function list(page = 0, size = NOTIFICATIONS_PAGE_SIZE): Promise<UserNotification[]> {
  const { data } = await api.get('/notifications', { params: { page, size } });
  const content = data && typeof data === 'object' ? (data as { content?: unknown }).content : undefined;
  if (!Array.isArray(content)) throw new MalformedNotificationResponse('page.content is not an array');
  return content.map(parseNotification).filter((n): n is UserNotification => n !== null);
}

/** GET /notifications/unread-count - the authoritative count (not limited to the loaded page). */
export async function unreadCount(): Promise<number> {
  const { data } = await api.get('/notifications/unread-count');
  const count = data && typeof data === 'object' ? (data as { count?: unknown }).count : undefined;
  if (typeof count !== 'number' || !Number.isInteger(count) || count < 0) {
    throw new MalformedNotificationResponse('unread-count.count is not a non-negative integer');
  }
  return count;
}

/** PATCH /notifications/{id}/read - the backend ignores ids of other users. */
export async function markRead(id: number): Promise<void> {
  await api.patch(`/notifications/${id}/read`);
}

/** PATCH /notifications/read-all */
export async function markAllRead(): Promise<void> {
  await api.patch('/notifications/read-all');
}
