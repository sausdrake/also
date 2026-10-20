import { parseStatus, type Parsed } from './parse';

/**
 * FE-STATE-01: diagnostic request state model. Source of truth:
 * backend DiagnosticRequest.DiagnosticRequestStatus and DiagnosticRequestService
 * (cancelRequest: PENDING only; saveInspectionAnswers: IN_PROGRESS with a bound template only;
 * resetForRework: REJECTED only; getRequestWithTemplate: refuses a request without a bound template).
 */
export const DIAGNOSTIC_REQUEST_STATUSES = ['PENDING', 'IN_PROGRESS', 'COMPLETED', 'APPROVED', 'REJECTED'] as const;
export type DiagnosticRequestStatus = (typeof DIAGNOSTIC_REQUEST_STATUSES)[number];

export const parseDiagnosticRequestStatus = (raw: unknown): Parsed<DiagnosticRequestStatus> =>
  parseStatus(DIAGNOSTIC_REQUEST_STATUSES, raw);

/** The owner (seller) and the diagnostician read the same status differently - the labels already used in the UI. */
export const DIAGNOSTIC_STATUS_LABELS: Record<'owner' | 'diagnostician', Record<DiagnosticRequestStatus, string>> = {
  owner: {
    PENDING: 'На рассмотрении',
    IN_PROGRESS: 'В работе',
    COMPLETED: 'На проверке',
    APPROVED: 'Одобрено',
    REJECTED: 'Отклонено',
  },
  diagnostician: {
    PENDING: 'Новая',
    IN_PROGRESS: 'В работе',
    COMPLETED: 'На проверке',
    APPROVED: 'Одобрен',
    REJECTED: 'Отклонён',
  },
};

export interface DiagnosticRequestState {
  status: Parsed<DiagnosticRequestStatus>;
  templateId: number | null | undefined;
}

/** The owner may withdraw the request only while no diagnostician has taken it. */
export const ownerCanCancel = (s: DiagnosticRequestState) => s.status.known && s.status.value === 'PENDING';

/** An accepted request whose questionnaire template is not chosen yet: the diagnostician must bind one first. */
export const needsTemplate = (s: DiagnosticRequestState) =>
  s.status.known && s.status.value === 'IN_PROGRESS' && (s.templateId === null || s.templateId === undefined);

/** Answers can be saved/submitted only in IN_PROGRESS and only against a bound template. */
export const diagnosticianCanEdit = (s: DiagnosticRequestState) =>
  s.status.known && s.status.value === 'IN_PROGRESS' && !needsTemplate(s);

/** A rejected report returns to editing only through the explicit "rework" action. */
export const diagnosticianCanRework = (s: DiagnosticRequestState) => s.status.known && s.status.value === 'REJECTED';
