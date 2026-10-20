import api, { BASE_URL } from './client';
import { parseAuctionStatus, type AuctionStatus } from '../domain/auction';
import type { Parsed } from '../domain/parse';

/**
 * FE-SUB-01: lot subscriptions ("Следить за лотом" / "Отслеживаемые"). The backend
 * LotSubscriptionController is mounted at /api/subscriptions - outside the /api/v1 prefix of the
 * shared client - so every call overrides baseURL while keeping the client's token and refresh
 * interceptors. All endpoints act on the caller's own subscriptions (the user comes from the
 * token; no user id is ever sent). Responses are validated: a malformed body is an error, never
 * an empty list or a "subscribed" state.
 */
// Resolved per call (not at import), so modules that only import a page using this file never touch BASE_URL.
const subscriptionsApi = () => ({ baseURL: `${BASE_URL}/api` });

export interface WatchedAuction {
  auctionId: number;
  vehicleInfo: string | null;
  currentPrice: number | null;
  status: Parsed<AuctionStatus>;
  /** ISO date-time, or null when absent or unparseable. */
  endTime: string | null;
  mainPhotoUrl: string | null;
}

export class MalformedSubscriptionResponse extends Error {
  constructor(what: string) {
    super(`Malformed subscriptions response: ${what}`);
    this.name = 'MalformedSubscriptionResponse';
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/** BigDecimal arrives as a JSON number (or a numeric string with some Jackson settings). */
const toAmount = (v: unknown): number | null => {
  const n = typeof v === 'number' ? v : typeof v === 'string' && v.trim() !== '' ? Number(v) : NaN;
  return Number.isFinite(n) ? n : null;
};

const toText = (v: unknown): string | null => (typeof v === 'string' && v.trim() !== '' ? v : null);

const toDateTime = (v: unknown): string | null =>
  typeof v === 'string' && !Number.isNaN(Date.parse(v)) ? v : null;

/** One list element, or null when it cannot identify a lot (it is dropped, the rest still render). */
export function parseWatchedAuction(raw: unknown): WatchedAuction | null {
  if (!isRecord(raw)) return null;
  const id = raw.auctionId;
  if (typeof id !== 'number' || !Number.isInteger(id) || id <= 0) return null;
  return {
    auctionId: id,
    vehicleInfo: toText(raw.vehicleInfo),
    currentPrice: toAmount(raw.currentPrice),
    status: parseAuctionStatus(raw.status),
    endTime: toDateTime(raw.endTime),
    mainPhotoUrl: toText(raw.mainPhotoUrl),
  };
}

/** GET /api/subscriptions - the caller's watched lots. */
export async function list(): Promise<WatchedAuction[]> {
  const { data } = await api.get('/subscriptions', subscriptionsApi());
  if (!Array.isArray(data)) throw new MalformedSubscriptionResponse('list is not an array');
  return data.map(parseWatchedAuction).filter((a): a is WatchedAuction => a !== null);
}

/** GET /api/subscriptions/{id}/status - true/false only when the backend says so explicitly. */
export async function status(auctionId: number): Promise<boolean> {
  const { data } = await api.get(`/subscriptions/${auctionId}/status`, subscriptionsApi());
  const subscribed = isRecord(data) ? data.subscribed : undefined;
  if (subscribed === true) return true;
  if (subscribed === false) return false;
  throw new MalformedSubscriptionResponse('status.subscribed is not a boolean');
}

/** POST /api/subscriptions/{id} - idempotent on the backend. */
export async function subscribe(auctionId: number): Promise<void> {
  await api.post(`/subscriptions/${auctionId}`, undefined, subscriptionsApi());
}

/** DELETE /api/subscriptions/{id} - idempotent on the backend. */
export async function unsubscribe(auctionId: number): Promise<void> {
  await api.delete(`/subscriptions/${auctionId}`, subscriptionsApi());
}
