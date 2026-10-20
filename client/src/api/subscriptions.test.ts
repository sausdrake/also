import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import axios, { type AxiosAdapter, type InternalAxiosRequestConfig } from 'axios';
import api from './client';
import * as subscriptions from './subscriptions';
import { parseApiError } from './errors';

/**
 * FE-SUB-01: the real shared client (interceptors included) is exercised; only the transport is
 * replaced, so these tests see the exact URL axios builds. Backend contract (LotSubscriptionController):
 * GET /api/subscriptions, GET /api/subscriptions/{id}/status, POST and DELETE /api/subscriptions/{id}
 * - outside /api/v1, which is what the old calls hit (404).
 */

type Reply = { status: number; data: unknown } | Error;
let calls: { method: string; url: string; auth: unknown }[] = [];
let reply: (config: InternalAxiosRequestConfig) => Reply = () => ({ status: 200, data: {} });
const originalAdapter = api.defaults.adapter;

beforeEach(() => {
  calls = [];
  localStorage.setItem('accessToken', 'test-token');
  const adapter: AxiosAdapter = async config => {
    calls.push({ method: (config.method ?? '').toUpperCase(), url: axios.getUri(config), auth: config.headers?.Authorization });
    const r = reply(config);
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

const BASE = (import.meta.env.VITE_API_URL || 'http://localhost:8081') + '/api';

describe('FE-SUB-01: subscriptions API hits the real backend paths', () => {
  it('every call uses /api/subscriptions, never /api/v1, with the bearer token', async () => {
    reply = c => (c.url === '/subscriptions' ? { status: 200, data: [] }
      : c.method === 'get' ? { status: 200, data: { subscribed: false } } : { status: c.method === 'delete' ? 204 : 200, data: '' });
    await subscriptions.list();
    await subscriptions.status(42);
    await subscriptions.subscribe(42);
    await subscriptions.unsubscribe(42);

    expect(calls.map(c => `${c.method} ${c.url}`)).toEqual([
      `GET ${BASE}/subscriptions`,
      `GET ${BASE}/subscriptions/42/status`,
      `POST ${BASE}/subscriptions/42`,
      `DELETE ${BASE}/subscriptions/42`,
    ]);
    expect(calls.every(c => !c.url.includes('/api/v1'))).toBe(true);
    expect(calls.every(c => c.auth === 'Bearer test-token')).toBe(true);
  });

  it('list maps the backend DTO and the auction status through the domain model', async () => {
    reply = () => ({ status: 200, data: [{ auctionId: 7, vehicleInfo: 'BMW X5 2020', currentPrice: 1500000, startingPrice: 1000000,
      status: 'ACTIVE', endTime: '2026-12-31T10:00:00Z', mainPhotoUrl: 'https://cdn/x.jpg' }] });
    expect(await subscriptions.list()).toEqual([{ auctionId: 7, vehicleInfo: 'BMW X5 2020', currentPrice: 1500000,
      status: { known: true, value: 'ACTIVE' }, endTime: '2026-12-31T10:00:00Z', mainPhotoUrl: 'https://cdn/x.jpg' }]);
  });

  it('status is true / false only for an explicit boolean', async () => {
    reply = () => ({ status: 200, data: { subscribed: true } });
    expect(await subscriptions.status(1)).toBe(true);
    reply = () => ({ status: 200, data: { subscribed: false } });
    expect(await subscriptions.status(1)).toBe(false);
  });
});

describe('FE-SUB-01 adversarial: malformed answers are errors, never empty or subscribed', () => {
  it.each([['"true"', 'true'], ['1', 1], ['"1"', '1'], ['object', { yes: true }], ['null', null], ['missing', undefined]])(
    'status with subscribed=%s is rejected', async (_name, value) => {
      reply = () => ({ status: 200, data: value === undefined ? {} : { subscribed: value } });
      await expect(subscriptions.status(1)).rejects.toBeInstanceOf(subscriptions.MalformedSubscriptionResponse);
    });

  it.each([['null', null], ['object', { content: [] }], ['string', '<html>oops</html>'], ['number', 5]])(
    'a list that is %s is an error, not an empty list', async (_name, data) => {
      reply = () => ({ status: 200, data });
      await expect(subscriptions.list()).rejects.toBeInstanceOf(subscriptions.MalformedSubscriptionResponse);
    });

  it('null / unusable elements are dropped; bad fields degrade to null; unknown status stays raw', async () => {
    reply = () => ({ status: 200, data: [
      null, 'x', { auctionId: '7' }, { auctionId: -1 }, { auctionId: 1.5 },
      { auctionId: 9, vehicleInfo: '', currentPrice: null, status: 'HACKED', endTime: 'not-a-date', mainPhotoUrl: 42 },
      { auctionId: 10, currentPrice: '250000.50', status: null },
    ] });
    expect(await subscriptions.list()).toEqual([
      { auctionId: 9, vehicleInfo: null, currentPrice: null, status: { known: false, raw: 'HACKED' }, endTime: null, mainPhotoUrl: null },
      { auctionId: 10, vehicleInfo: null, currentPrice: 250000.5, status: { known: false, raw: 'null' }, endTime: null, mainPhotoUrl: null },
    ]);
  });

  it.each([[404, 'not_found'], [409, 'conflict'], [500, 'server']])('HTTP %i propagates as a typed error', async (code, kind) => {
    reply = () => ({ status: code, data: { message: 'Ошибка сервера' } });
    const error = await subscriptions.list().catch(e => e);
    expect(parseApiError(error).kind).toBe(kind);
  });

  it('401 without a refresh token is rejected as unauthorized (no fake empty list)', async () => {
    reply = () => ({ status: 401, data: { error: 'Unauthorized', message: 'Full authentication is required' } });
    const error = await subscriptions.list().catch(e => e);
    expect(parseApiError(error).kind).toBe('unauthorized');
    expect(calls).toHaveLength(1);
  });

  it('a network failure propagates as kind network', async () => {
    reply = () => new Error('Network Error');
    const error = await subscriptions.subscribe(3).catch(e => e);
    expect(parseApiError(error).kind).toBe('network');
  });
});
