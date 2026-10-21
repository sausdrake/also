import { parseStatus, type Parsed } from './parse';

/**
 * FE-STATE-01: identity verification (KYC) state model. Source of truth: backend
 * IdentityVerificationStatus and IdentityVerification (a first submission when none exists;
 * resubmit only from REJECTED or REVOKED). GET /identity/me answers 404 when nothing was submitted,
 * which is the `NOT_SUBMITTED` state here. Only VERIFIED unlocks the KYC-gated operations
 * (bids, Buy Now, diagnostic requests, withdrawals - VerifiedIdentityGate).
 */
export const IDENTITY_STATUSES = ['PENDING_REVIEW', 'VERIFIED', 'REJECTED', 'REVOKED'] as const;
export type IdentityStatus = (typeof IDENTITY_STATUSES)[number];

export const parseIdentityStatus = (raw: unknown): Parsed<IdentityStatus> => parseStatus(IDENTITY_STATUSES, raw);

export const IDENTITY_STATUS_LABELS: Record<IdentityStatus, string> = {
  PENDING_REVIEW: 'На рассмотрении',
  VERIFIED: 'Подтверждено',
  REJECTED: 'Отклонено',
  REVOKED: 'Отозвано',
};

/** `null` = nothing submitted yet (GET /identity/me returned 404). */
export type KycState = Parsed<IdentityStatus> | null;

export const kycCanSubmit = (s: KycState) =>
  s === null || (s.known && (s.value === 'REJECTED' || s.value === 'REVOKED'));

export const kycIsVerified = (s: KycState) => s !== null && s.known && s.value === 'VERIFIED';

/** KYC-CAPTURE-01: how each camera step is named to the user (which steps a document needs comes from the backend). */
export const CAPTURE_SIDE_LABELS: Record<'FRONT' | 'BACK' | 'DATA_PAGE' | 'SELFIE', string> = {
  FRONT: 'Лицевая сторона',
  BACK: 'Обратная сторона',
  DATA_PAGE: 'Страница с фотографией и данными',
  SELFIE: 'Селфи',
};
