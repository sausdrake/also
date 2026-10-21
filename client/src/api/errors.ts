/**
 * FE-ERR-01: one parser for every error the API can answer with (GlobalExceptionHandler,
 * JwtAuthenticationEntryPoint, JwtAccessDeniedHandler). Callers stop reading `data.message` ad hoc:
 * a validation error (400 `details`, no `message`), an error code (`IDENTITY_VERIFICATION_REQUIRED`,
 * `PASSWORD_CHANGE_REQUIRED`), a lost connection and an English Spring Security text all become a
 * typed kind and a message the user can read.
 */
export type ApiErrorKind =
  | 'network' | 'validation' | 'unauthorized' | 'forbidden' | 'kyc_required' | 'trusted_required' | 'password_change_required'
  | 'not_found' | 'conflict' | 'insufficient_funds' | 'rate_limited' | 'server' | 'unknown';

export interface ApiError {
  kind: ApiErrorKind;
  status: number | null;
  code: string | null;
  message: string;
  /** Field -> message, from a 400 `details` body; empty otherwise. */
  fieldErrors: Record<string, string>;
}

const DEFAULT_MESSAGES: Record<ApiErrorKind, string> = {
  network: 'Нет связи с сервером. Проверьте подключение и повторите попытку.',
  validation: 'Проверьте введённые данные.',
  unauthorized: 'Требуется вход в систему.',
  forbidden: 'Недостаточно прав для этого действия.',
  kyc_required: 'Операция доступна только после подтверждения личности.',
  trusted_required: 'Для участия в торгах нужен статус доверенного пользователя.',
  password_change_required: 'Сначала смените временный пароль.',
  not_found: 'Запрошенные данные не найдены.',
  conflict: 'Действие сейчас недоступно: данные изменились. Обновите страницу.',
  insufficient_funds: 'Недостаточно средств на депозите.',
  rate_limited: 'Слишком много запросов. Подождите немного и повторите.',
  server: 'Сервер не смог выполнить запрос. Повторите попытку позже.',
  unknown: 'Не удалось выполнить действие.',
};

const CYRILLIC = /[А-Яа-яЁё]/;
const MAX_MESSAGE = 500;

/** Backend text is shown only when it is a user-facing Russian sentence (never English stack-ish text, never HTML). */
function usable(text: unknown): string | null {
  if (typeof text !== 'string') return null;
  const t = text.trim();
  if (!t || !CYRILLIC.test(t) || /<[a-z!/][^>]*>/i.test(t)) return null;
  return t.length > MAX_MESSAGE ? t.slice(0, MAX_MESSAGE) + '…' : t;
}

function fieldErrorsOf(details: unknown): Record<string, string> {
  if (!details || typeof details !== 'object' || Array.isArray(details)) return {};
  const out: Record<string, string> = {};
  for (const [field, msg] of Object.entries(details as Record<string, unknown>)) {
    if (typeof msg === 'string' && msg.trim()) out[field] = msg.trim();
  }
  return out;
}

function kindOf(status: number | null, code: string | null, errorField: string | null): ApiErrorKind {
  if (status === null) return 'network';
  if (code === 'IDENTITY_VERIFICATION_REQUIRED') return 'kyc_required';
  // KYC-01: the separate trust gate (bid / Buy Now) - never dressed up as a KYC refusal
  if (code === 'TRUSTED_PARTICIPANT_REQUIRED') return 'trusted_required';
  if (status === 403 && errorField === 'PASSWORD_CHANGE_REQUIRED') return 'password_change_required';
  if (status === 400) return 'validation';
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 409) return 'conflict';
  if (status === 422) return 'insufficient_funds';
  if (status === 429) return 'rate_limited';
  if (status >= 500) return 'server';
  return 'unknown';
}

export function parseApiError(e: unknown, fallback?: string): ApiError {
  const err = e as { response?: { status?: unknown; data?: unknown }; request?: unknown; isAxiosError?: unknown } | null;
  const response = err && typeof err === 'object' ? err.response : undefined;
  const isTransport = !!err && typeof err === 'object' && !response && (err.request !== undefined || err.isAxiosError === true);
  if (!response) {
    const kind: ApiErrorKind = isTransport ? 'network' : 'unknown';
    return { kind, status: null, code: null, message: kind === 'unknown' && fallback ? fallback : DEFAULT_MESSAGES[kind], fieldErrors: {} };
  }
  const status = typeof response.status === 'number' ? response.status : null;
  const data = response.data && typeof response.data === 'object' && !Array.isArray(response.data)
    ? (response.data as Record<string, unknown>) : {};
  const code = typeof data.code === 'string' ? data.code : null;
  const errorField = typeof data.error === 'string' ? data.error : null;
  const kind = status === null ? 'unknown' : kindOf(status, code, errorField);
  const fieldErrors = kind === 'validation' ? fieldErrorsOf(data.details) : {};

  let message = usable(data.message);
  if (!message && kind === 'validation' && Object.keys(fieldErrors).length) {
    message = Object.values(fieldErrors).join('; ');
  }
  if (!message) message = fallback && kind === 'unknown' ? fallback : DEFAULT_MESSAGES[kind];
  return { kind, status, code, message, fieldErrors };
}

/** Convenience for the common `catch (e) { setMsg(...) }`. */
export const apiErrorMessage = (e: unknown, fallback?: string) => parseApiError(e, fallback).message;
