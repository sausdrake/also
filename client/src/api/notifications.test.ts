import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import axios, { type AxiosAdapter } from 'axios';
import api from './client';
import * as notifications from './notifications';
import { parseApiError } from './errors';

/** FE-NOTIF-01: the real shared client is used; only the transport is replaced to see the exact requests. */

type Reply = { status: number; data: unknown } | Error;
let calls: string[] = [];
let reply: (method: string, url: string) => Reply = () => ({ status: 200, data: {} });
const originalAdapter = api.defaults.adapter;
const BASE = (import.meta.env.VITE_API_URL || 'http://localhost:8081') + '/api/v1';

beforeEach(() => {
  calls = [];
  localStorage.setItem('accessToken', 'test-token');
  const adapter: AxiosAdapter = async config => {
    const url = axios.getUri(config);
    const method = (config.method ?? '').toUpperCase();
    calls.push(`${method} ${url}`);
    const r = reply(method, url);
    if (r instanceof Error) throw Object.assign(r, { isAxiosError: true, request: {}, config });
    const response = { data: r.data, status: r.status, statusText: '', headers: {}, config };
    if (r.status >= 400) throw Object.assign(new Error(`HTTP ${r.status}`), { isAxiosError: true, response, config });
    return response;
  };
  api.defaults.adapter = adapter;
});

afterEach(() => {
  api.defaults.adapter = originalAdapter;
  localStorage.clear();
});

const page = (content: unknown) => ({ content, totalElements: Array.isArray(content) ? content.length : 0, number: 0, size: 20 });

describe('FE-NOTIF-01: notifications API', () => {
  it('uses the backend paths and methods', async () => {
    reply = (_m, url) => url.endsWith('unread-count') ? { status: 200, data: { count: 3 } }
      : url.includes('/notifications?') ? { status: 200, data: page([]) } : { status: 204, data: '' };
    await notifications.list();
    await notifications.unreadCount();
    await notifications.markRead(7);
    await notifications.markAllRead();
    expect(calls).toEqual([
      `GET ${BASE}/notifications?page=0&size=20`,
      `GET ${BASE}/notifications/unread-count`,
      `PATCH ${BASE}/notifications/7/read`,
      `PATCH ${BASE}/notifications/read-all`,
    ]);
  });

  it('list returns validated page content; unread-count returns the backend number', async () => {
    reply = (_m, url) => url.endsWith('unread-count') ? { status: 200, data: { count: 25 } }
      : { status: 200, data: page([{ id: 1, type: 'OUTBID', title: 'T', message: 'M', read: false, createdAt: '2026-10-20T10:00:00', referenceId: 4, referenceType: 'AUCTION' }, null, { id: 'x' }]) };
    expect(await notifications.list()).toHaveLength(1);
    expect(await notifications.unreadCount()).toBe(25);
  });
});

describe('FE-NOTIF-01 adversarial: malformed answers are errors, never an empty inbox or 0 unread', () => {
  it.each([['null', null], ['array instead of a page', []], ['content null', { content: null }], ['HTML', '<html/>']])(
    'list body %s', async (_n, data) => {
      reply = () => ({ status: 200, data });
      await expect(notifications.list()).rejects.toBeInstanceOf(notifications.MalformedNotificationResponse);
    });

  it.each([['missing', {}], ['string', { count: '3' }], ['negative', { count: -1 }], ['fraction', { count: 1.5 }], ['null body', null]])(
    'unread-count %s', async (_n, data) => {
      reply = () => ({ status: 200, data });
      await expect(notifications.unreadCount()).rejects.toBeInstanceOf(notifications.MalformedNotificationResponse);
    });

  it.each([[403, 'forbidden'], [404, 'not_found'], [500, 'server']])('HTTP %i is a typed error', async (code, kind) => {
    reply = () => ({ status: code, data: {} });
    expect(parseApiError(await notifications.markRead(1).catch(e => e)).kind).toBe(kind);
  });

  it('401 without a refresh token is rejected as unauthorized', async () => {
    reply = () => ({ status: 401, data: { error: 'Unauthorized' } });
    expect(parseApiError(await notifications.list().catch(e => e)).kind).toBe('unauthorized');
  });

  it('a network failure is kind network', async () => {
    reply = () => new Error('Network Error');
    expect(parseApiError(await notifications.unreadCount().catch(e => e)).kind).toBe('network');
  });
});
