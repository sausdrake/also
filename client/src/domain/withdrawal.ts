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
