import { useState, useEffect, useCallback, useRef } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';

const BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8081';

export interface Notification {
  id: number;
  type: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
  referenceId?: number;
  referenceType?: string;
}

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
    return false;
  }
}

export function useNotifications() {
  const { isAuthenticated, user } = useAuthStore();
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [unreadCount, setUnreadCount] = useState(0);
  const wsClientRef = useRef<Client | null>(null);

  const load = useCallback(async () => {
    if (!isAuthenticated || !isAccessTokenValid()) return;
    try {
      const { data } = await api.get('/notifications?page=0&size=20');
      const items: Notification[] = data.content ?? data ?? [];
      setNotifications(items);
      setUnreadCount(items.filter((n) => !n.read).length);
    } catch {}
  }, [isAuthenticated]);

  // WebSocket subscription for real-time notifications
  useEffect(() => {
    if (!isAuthenticated || !user?.id) return;

    const token = localStorage.getItem('accessToken');
    if (!token) return;

    const client = new Client({
      webSocketFactory: () => new SockJS(`${BASE_URL}/ws-auction`) as any,
      debug: () => {},
      reconnectDelay: 5000,
      connectHeaders: { Authorization: `Bearer ${token}` },
      onConnect: () => {
        // Server routes via convertAndSendToUser → only this session receives its own notifications
        client.subscribe('/user/queue/notifications', (frame) => {
          try {
            const notification: Notification = JSON.parse(frame.body);
            setNotifications(prev => {
              if (prev.some(n => n.id === notification.id)) return prev;
              return [notification, ...prev].slice(0, 50);
            });
            if (!notification.read) {
              setUnreadCount(c => c + 1);
            }
          } catch {}
        });
      },
    });

    client.activate();
    wsClientRef.current = client;

    return () => {
      wsClientRef.current?.deactivate();
      wsClientRef.current = null;
    };
  }, [isAuthenticated, user?.id]);

  const markRead = async (id: number) => {
    if (!isAccessTokenValid()) return;
    try {
      await api.patch(`/notifications/${id}/read`);
      setNotifications((prev) =>
        prev.map((n) => (n.id === id ? { ...n, read: true } : n))
      );
      setUnreadCount((c) => Math.max(0, c - 1));
    } catch {}
  };

  const markAllRead = async () => {
    if (!isAccessTokenValid()) return;
    try {
      await api.patch('/notifications/read-all');
      setNotifications((prev) => prev.map((n) => ({ ...n, read: true })));
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
