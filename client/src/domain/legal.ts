/**
 * LEGAL-DATA-01 / PRIVACY-REQ-01: document and request types, names and labels (no network code, so pages and
 * tests can use them without the API client).
 */

export type LegalDocumentType =
  | 'TERMS_OF_USE' | 'PRIVACY_POLICY' | 'PERSONAL_DATA_PROCESSING_POLICY' | 'AUCTION_RULES'
  | 'KYC_POLICY' | 'DIAGNOSTIC_RULES' | 'FINANCIAL_RULES';

export type AcceptanceKind = 'ACCEPTANCE' | 'ACKNOWLEDGEMENT' | 'CONSENT' | 'INFORMATIONAL';

export interface LegalDocument {
  id: number;
  documentType: LegalDocumentType;
  version: string;
  title: string;
  acceptanceKind: AcceptanceKind;
  effectiveAt: string | null;
  contentSha256: string;
  content: string | null;
}

export interface Acceptance {
  id: number;
  documentVersionId: number;
  documentType: LegalDocumentType;
  documentVersion: string;
  kind: AcceptanceKind;
  status: 'ACCEPTED' | 'WITHDRAWN';
  source: string;
  acceptedAt: string;
  withdrawnAt: string | null;
}

/** URL slug of each document (the page /legal/:slug). */
export const LEGAL_SLUGS: Record<LegalDocumentType, string> = {
  TERMS_OF_USE: 'terms',
  PRIVACY_POLICY: 'privacy',
  PERSONAL_DATA_PROCESSING_POLICY: 'personal-data',
  AUCTION_RULES: 'auction-rules',
  KYC_POLICY: 'kyc',
  DIAGNOSTIC_RULES: 'diagnostic-rules',
  FINANCIAL_RULES: 'financial-rules',
};

export const LEGAL_NAMES: Record<LegalDocumentType, string> = {
  TERMS_OF_USE: 'Пользовательское соглашение',
  PRIVACY_POLICY: 'Политика конфиденциальности',
  PERSONAL_DATA_PROCESSING_POLICY: 'Положение об обработке персональных данных',
  AUCTION_RULES: 'Правила аукционов',
  KYC_POLICY: 'Политика подтверждения личности (KYC)',
  DIAGNOSTIC_RULES: 'Правила диагностики',
  FINANCIAL_RULES: 'Правила депозита и вывода средств',
};

/** What the user's action means, in the words shown next to the checkbox / button. */
export const ACCEPTANCE_VERB: Record<AcceptanceKind, string> = {
  ACCEPTANCE: 'Принимаю',
  ACKNOWLEDGEMENT: 'Ознакомлен(а)',
  CONSENT: 'Даю согласие',
  INFORMATIONAL: '',
};

export function typeBySlug(slug: string | undefined): LegalDocumentType | null {
  const entry = Object.entries(LEGAL_SLUGS).find(([, s]) => s === slug);
  return entry ? (entry[0] as LegalDocumentType) : null;
}

// ─── privacy requests ────────────────────────────────────────────────────────

export type PrivacyRequestType = 'ACCESS' | 'CORRECTION' | 'DELETION' | 'OBJECTION' | 'CONSENT_WITHDRAWAL' | 'OTHER';
export type PrivacyRequestStatus = 'OPEN' | 'IN_PROGRESS' | 'ANSWERED' | 'REJECTED';

export interface PrivacyRequest {
  id: number;
  userId: number;
  type: PrivacyRequestType;
  message: string;
  status: PrivacyRequestStatus;
  createdAt: string;
  takenAt: string | null;
  answer: string | null;
  answeredAt: string | null;
}

export const PRIVACY_REQUEST_TYPES: Record<PrivacyRequestType, string> = {
  ACCESS: 'Какие мои данные хранятся',
  CORRECTION: 'Исправить мои данные',
  DELETION: 'Удалить мои данные',
  OBJECTION: 'Возражение против обработки',
  CONSENT_WITHDRAWAL: 'Отзыв согласия',
  OTHER: 'Другое / жалоба',
};

export const PRIVACY_REQUEST_STATUSES: Record<PrivacyRequestStatus, string> = {
  OPEN: 'Новое',
  IN_PROGRESS: 'В работе',
  ANSWERED: 'Дан ответ',
  REJECTED: 'Отказано',
};
