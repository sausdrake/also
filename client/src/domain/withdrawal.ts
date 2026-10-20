import { parseStatus, type Parsed } from './parse';

/**
 * FE-STATE-01: withdrawal request (F6 manual payout) state model. Source of truth: backend
 * WithdrawalRequestStatus and WithdrawalRequest: startProcessing PENDING -> PROCESSING,
 * markPaid PROCESSING -> PAID (payout note required), reject PENDING|PROCESSING -> REJECTED
 * (reason + explicit "no payout made"). PAID and REJECTED are final.
 */
export const WITHDRAWAL_STATUSES = ['PENDING', 'PROCESSING', 'PAID', 'REJECTED'] as const;
export type WithdrawalStatus = (typeof WITHDRAWAL_STATUSES)[number];

export const parseWithdrawalStatus = (raw: unknown): Parsed<WithdrawalStatus> => parseStatus(WITHDRAWAL_STATUSES, raw);

export const WITHDRAWAL_STATUS_LABELS: Record<WithdrawalStatus, string> = {
  PENDING: 'Ожидает',
  PROCESSING: 'В обработке',
  PAID: 'Выплачено',
  REJECTED: 'Отклонено',
};

export type WithdrawalAdminAction = 'processing' | 'paid' | 'reject';

const ADMIN_ACTIONS: Record<WithdrawalStatus, readonly WithdrawalAdminAction[]> = {
  PENDING: ['processing', 'reject'],
  PROCESSING: ['paid', 'reject'],
  PAID: [],
  REJECTED: [],
};

/** Actions an administrator may take; an unknown status allows none. */
export const withdrawalAdminActions = (s: Parsed<WithdrawalStatus>): readonly WithdrawalAdminAction[] =>
  s.known ? ADMIN_ACTIONS[s.value] : [];

export const withdrawalIsFinal = (s: Parsed<WithdrawalStatus>) => s.known && (s.value === 'PAID' || s.value === 'REJECTED');

/**
 * FE-WD-01: the same statuses as the owner reads them (profile, own requests): a rejection returns
 * the amount to the deposit (WithdrawalRequestService.reject), and PENDING waits for an admin.
 */
export const WITHDRAWAL_OWNER_LABELS: Record<WithdrawalStatus, string> = {
  PENDING: 'Ожидает обработки',
  PROCESSING: 'В обработке',
  PAID: 'Выплачено',
  REJECTED: 'Отклонено, сумма возвращена',
};

const AMOUNT = /^\d+(?:[.,]\d+)?$/;

/** The amount as the backend will receive it, or null when the input is not a plain decimal number. */
export function toWithdrawalAmount(raw: string): number | null {
  const t = raw.trim();
  if (!AMOUNT.test(t)) return null;
  const n = Number(t.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

/**
 * FE-WD-01: client-side check mirroring the backend WithdrawRequest (@NotNull, @DecimalMin 0.01,
 * @Digits fraction 2) plus the available balance the form shows. The backend stays authoritative;
 * this only avoids sending a request it will certainly refuse. `available` null = not loaded yet.
 */
export function validateWithdrawalAmount(raw: string, available: number | null): string | null {
  const t = raw.trim();
  if (!t) return 'Введите сумму';
  if (!AMOUNT.test(t)) return 'Введите сумму числом, например 1500 или 1500.50';
  if (/[.,]\d{3,}$/.test(t)) return 'Не больше двух знаков после запятой';
  const value = toWithdrawalAmount(t);
  if (value === null || value < 0.01) return 'Сумма должна быть больше 0';
  if (available === null) return 'Доступный баланс ещё не загружен';
  if (value > available) return `Максимум: ${available.toLocaleString('ru')} сом`;
  return null;
}
