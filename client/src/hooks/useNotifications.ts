import { useState, useEffect, useCallback } from 'react';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';

export interface Notification {
  id: number;
  type: string;
  title: string;
  message: string;
  isRead: boolean;
  createdAt: string;
}

function isAccessTokenValid(): boolean {
  try {
    const token = localStorage.getItem('accessToken');
    if (!token) return false;
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')));
    if (!payload?.exp) return false;
    // Добавляем 10-секундный буфер чтобы не запрашивать данные с токеном на грани истечения
    return Date.now() / 1000 < payload.exp - 10;
  } catch {
    return false;
  }
}

export function useNotifications() {
  const { isAuthenticated } = useAuthStore();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);

  const load = useCallback(async () => {
    // Запрашиваем только если авторизован И токен реально валиден
    if (!isAuthenticated || !isAccessTokenValid()) return;

    try {
      const { data } = await api.get('/notifications?page=0&size=10');
      const items: Notification[] = data.content ?? data ?? [];
      setNotifications(items);
      setUnreadCount(items.filter((n) => !n.isRead).length);
    } catch {
      // Уведомления некритичны — любые ошибки молча игнорируем
    }
  }, [isAuthenticated]);

  const markRead = async (id: number) => {
    if (!isAccessTokenValid()) return;
    try {
      await api.patch(`/notifications/${id}/read`);
      setNotifications((prev) =>
        prev.map((n) => (n.id === id ? { ...n, isRead: true } : n))
      );
      setUnreadCount((c) => Math.max(0, c - 1));
    } catch {}
  };

  const markAllRead = async () => {
    if (!isAccessTokenValid()) return;
    try {
      await api.patch('/notifications/read-all');
      setNotifications((prev) => prev.map((n) => ({ ...n, isRead: true })));
      setUnreadCount(0);
    } catch {}
  };

  useEffect(() => {
    load();
    const interval = setInterval(load, 30_000);
    return () => clearInterval(interval);
  }, [load]);

  return { notifications, unreadCount, markRead, markAllRead, reload: load };
}
