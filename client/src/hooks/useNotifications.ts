import { useState, useEffect, useCallback, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { BASE_URL } from '../api/client';
import * as notificationsApi from '../api/notifications';
import { apiErrorMessage } from '../api/errors';
import { parseNotification, type UserNotification } from '../domain/notification';
import { useAuthStore } from '../store/authStore';

export type { UserNotification as Notification } from '../domain/notification';

function isAccessTokenValid(): boolean {
  try {
    const token = localStorage.getItem('accessToken');
    if (!token) return false;
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (!payload?.exp) return false;
    return Date.now() / 1000 < payload.exp - 10;
  } catch {
    // An unreadable token is simply "not valid" - the background poll waits for a fresh one.
    return false;
  }
}

/** 'loading' until the first answer; 'error' keeps the last good items but is shown as an error. */
export type NotificationsStatus = 'loading' | 'ready' | 'error';

/**
 * FE-NOTIF-01: inbox state. The unread badge comes from GET /unread-count (not from the 20 loaded
 * items); a failed load is an error state, never "Нет уведомлений"; read/read-all failures are
 * reported; STOMP payloads are validated and de-duplicated by id, so a pushed notification is
 * counted once. The background poll still waits for a valid access token (it must not trigger a
 * refresh/logout cascade on its own); user actions go through the client's refresh interceptor.
 */
export function useNotifications() {
  const { isAuthenticated, user } = useAuthStore();
  const [notifications, setNotifications] = useState<UserNotification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const [status, setStatus] = useState<NotificationsStatus>('loading');
  const [loadError, setLoadError] = useState('');
  const [actionError, setActionError] = useState('');
  const itemsRef = useRef<UserNotification[]>([]);
  const seenIds = useRef<Set<number>>(new Set());
  const pendingRead = useRef<Set<number>>(new Set());

  useEffect(() => { itemsRef.current = notifications; }, [notifications]);

  const load = useCallback(async () => {
    if (!isAuthenticated || !isAccessTokenValid()) return;
    try {
      const [items, count] = await Promise.all([notificationsApi.list(), notificationsApi.unreadCount()]);
      items.forEach(n => seenIds.current.add(n.id));
      setNotifications(items);
      setUnreadCount(count);
      setLoadError('');
      setStatus('ready');
    } catch (e) {
      setLoadError(apiErrorMessage(e, 'Не удалось загрузить уведомления'));
      setStatus('error');
    }
  }, [isAuthenticated]);

  // WebSocket subscription for real-time notifications
  useEffect(() => {
    if (!isAuthenticated || !user?.id) return;

    const token = localStorage.getItem('accessToken');
    if (!token) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(`${BASE_URL}/ws-auction`) as WebSocket,
      debug: () => {},
      reconnectDelay: 5000,
      connectHeaders: { Authorization: `Bearer ${token}` },
      onConnect: () => {
        // Server routes via convertAndSendToUser → only this session receives its own notifications
        client.subscribe('/user/queue/notifications', (frame) => {
          let raw: unknown;
          try {
            raw = JSON.parse(frame.body);
          } catch {
            // A non-JSON frame is not a notification; the next poll reconciles the inbox.
            return;
          }
          const notification = parseNotification(raw);
          if (!notification || seenIds.current.has(notification.id)) return;
          seenIds.current.add(notification.id);
          setNotifications(prev => [notification, ...prev.filter(n => n.id !== notification.id)].slice(0, 50));
          if (!notification.read) setUnreadCount(c => c + 1);
        });
      },
    });

    client.activate();

    return () => {
      client.deactivate();
    };
  }, [isAuthenticated, user?.id]);

  const markRead = async (id: number) => {
    if (pendingRead.current.has(id)) return;
    const wasUnread = itemsRef.current.some(n => n.id === id && !n.read);
    pendingRead.current.add(id);
    try {
      await notificationsApi.markRead(id);
      setNotifications(prev => prev.map(n => (n.id === id ? { ...n, read: true } : n)));
      if (wasUnread) setUnreadCount(c => Math.max(0, c - 1));
      setActionError('');
    } catch (e) {
      setActionError(apiErrorMessage(e, 'Не удалось отметить уведомление прочитанным'));
    } finally {
      pendingRead.current.delete(id);
    }
  };

  const markAllRead = async () => {
    try {
      await notificationsApi.markAllRead();
      setNotifications(prev => prev.map(n => ({ ...n, read: true })));
      setUnreadCount(0);
      setActionError('');
      // A notification pushed while the request was in flight would be unread on the server.
      void load();
    } catch (e) {
      setActionError(apiErrorMessage(e, 'Не удалось отметить уведомления прочитанными'));
    }
  };

  useEffect(() => {
    const first = setTimeout(load, 0);
    const interval = setInterval(load, 30_000);
    return () => {
      clearTimeout(first);
      clearInterval(interval);
    };
  }, [load]);

  return { notifications, unreadCount, status, loadError, actionError, markRead, markAllRead, reload: load };
}
