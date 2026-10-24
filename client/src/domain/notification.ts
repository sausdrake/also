/**
 * FE-NOTIF-01: user notification model. Source of truth: the backend NotificationService.send(...)
 * call sites (types, recipients, referenceType) and the Notification DTO (id, type, title, message,
 * read, createdAt, referenceId, referenceType). The type list is checked against those call sites by
 * notification.contract.test.ts, so a new backend type cannot silently fall into "Система".
 */
export const NOTIFICATION_TYPES = [
  'NEW_BID_ON_VEHICLE', 'OUTBID', 'AUCTION_LEADER', 'AUCTION_WON', 'AUCTION_LOST', 'AUCTION_ENDING_SOON',
  'AUCTION_ENDED', 'AUCTION_COMPLETED', 'AUCTION_CANCELLED',
  'DEPOSIT_FROZEN', 'DEPOSIT_UNFROZEN', 'DEPOSIT_RELEASED',
  'DIAG_ACCEPTED', 'DIAG_REPORT_SUBMITTED', 'DIAG_REPORT_APPROVED', 'DIAG_REPORT_REJECTED', 'DIAG_REDIAGNOSTIC_ORDERED',
  'PRIVACY_REQUEST_ANSWERED',
] as const;
export type NotificationType = (typeof NOTIFICATION_TYPES)[number];

export type NotificationCategory = 'AUCTIONS' | 'DEPOSITS' | 'DIAGNOSTIC' | 'SYSTEM';

export interface UserNotification {
  id: number;
  /** Raw backend type; an unknown one is kept (shown under "Система", no link). */
  type: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string | null;
  referenceId: number | null;
  referenceType: string | null;
}

const isKnownType = (t: string): t is NotificationType => (NOTIFICATION_TYPES as readonly string[]).includes(t);

const CATEGORY: Record<NotificationType, NotificationCategory> = {
  NEW_BID_ON_VEHICLE: 'AUCTIONS', OUTBID: 'AUCTIONS', AUCTION_LEADER: 'AUCTIONS', AUCTION_WON: 'AUCTIONS',
  AUCTION_LOST: 'AUCTIONS', AUCTION_ENDING_SOON: 'AUCTIONS', AUCTION_ENDED: 'AUCTIONS', AUCTION_COMPLETED: 'AUCTIONS',
  AUCTION_CANCELLED: 'AUCTIONS',
  DEPOSIT_FROZEN: 'DEPOSITS', DEPOSIT_UNFROZEN: 'DEPOSITS', DEPOSIT_RELEASED: 'DEPOSITS',
  DIAG_ACCEPTED: 'DIAGNOSTIC', DIAG_REPORT_SUBMITTED: 'DIAGNOSTIC', DIAG_REPORT_APPROVED: 'DIAGNOSTIC',
  DIAG_REPORT_REJECTED: 'DIAGNOSTIC', DIAG_REDIAGNOSTIC_ORDERED: 'DIAGNOSTIC',
  PRIVACY_REQUEST_ANSWERED: 'SYSTEM',
};

export const notificationCategory = (n: Pick<UserNotification, 'type'>): NotificationCategory =>
  isKnownType(n.type) ? CATEGORY[n.type] : 'SYSTEM';

/**
 * Where a notification leads. Decided by TYPE, because the type fixes the recipient: a DIAGNOSTIC
 * reference reaches the seller (accepted / approved / re-diagnostic -> their requests), the admins
 * (report submitted -> report review) or the diagnostician (report rejected -> their reports).
 * Deposit notifications carry no reference -> the deposit tab. The message text is never parsed.
 */
export function notificationLink(n: Pick<UserNotification, 'type' | 'referenceId' | 'referenceType'>): string | null {
  const ref = n.referenceId;
  const hasRef = typeof ref === 'number' && Number.isInteger(ref) && ref > 0;
  if (!isKnownType(n.type)) return null;
  switch (CATEGORY[n.type]) {
    case 'AUCTIONS':
      return hasRef && n.referenceType === 'AUCTION' ? `/auction/${ref}` : null;
    case 'DEPOSITS':
      return '/profile';
    case 'DIAGNOSTIC':
      if (n.type === 'DIAG_REPORT_SUBMITTED') return '/admin/diagnostic/reports';
      if (n.type === 'DIAG_REPORT_REJECTED') return '/diagnostic/reports';
      return '/profile/requests';
    default:
      // PRIVACY-REQ-01: the answer is read in the profile's data tab
      return n.type === 'PRIVACY_REQUEST_ANSWERED' ? '/profile/privacy' : null;
  }
}

const isRecord = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/** One notification from REST or STOMP, or null when it has no usable id/type (it is dropped). */
export function parseNotification(raw: unknown): UserNotification | null {
  if (!isRecord(raw)) return null;
  const { id, type } = raw;
  if (typeof id !== 'number' || !Number.isInteger(id) || id <= 0) return null;
  if (typeof type !== 'string' || !type.trim()) return null;
  const refId = raw.referenceId;
  return {
    id,
    type,
    title: typeof raw.title === 'string' ? raw.title : '',
    message: typeof raw.message === 'string' ? raw.message : '',
    read: raw.read === true,
    createdAt: typeof raw.createdAt === 'string' ? raw.createdAt : null,
    referenceId: typeof refId === 'number' && Number.isInteger(refId) && refId > 0 ? refId : null,
    referenceType: typeof raw.referenceType === 'string' ? raw.referenceType : null,
  };
}
