import { describe, expect, it } from 'vitest';
import { apiErrorMessage, parseApiError } from './errors';

const res = (status: number, data: unknown) => ({ isAxiosError: true, response: { status, data } });

describe('FE-ERR-01: every backend error shape becomes a typed kind and a readable message', () => {
  it('400 validation: details without message -> field errors joined, kind validation', () => {
    const e = parseApiError(res(400, { status: 400, error: 'Ошибка валидации',
      details: { vin: 'VIN должен содержать 17 символов', brand: 'размер должен находиться в диапазоне от 2 до 100' } }));
    expect(e.kind).toBe('validation');
    expect(e.fieldErrors).toEqual({ vin: 'VIN должен содержать 17 символов', brand: 'размер должен находиться в диапазоне от 2 до 100' });
    expect(e.message).toBe('VIN должен содержать 17 символов; размер должен находиться в диапазоне от 2 до 100');
  });

  it('403 IDENTITY_VERIFICATION_REQUIRED -> kyc_required with the backend Russian message', () => {
    const e = parseApiError(res(403, { status: 403, error: 'Forbidden', code: 'IDENTITY_VERIFICATION_REQUIRED',
      message: 'Операция доступна только после подтверждения личности (KYC). Пройдите верификацию личности.' }));
    expect(e).toMatchObject({ kind: 'kyc_required', status: 403, code: 'IDENTITY_VERIFICATION_REQUIRED' });
    expect(e.message).toContain('подтверждения личности');
  });

  it('403 PASSWORD_CHANGE_REQUIRED (error field) -> password_change_required', () => {
    expect(parseApiError(res(403, { error: 'PASSWORD_CHANGE_REQUIRED' })).kind).toBe('password_change_required');
  });

  it('KYC-01: 403 TRUSTED_PARTICIPANT_REQUIRED -> trusted_required (not kyc_required, not plain forbidden)', () => {
    const msg = 'Для участия в торгах нужен статус доверенного пользователя.';
    expect(parseApiError(res(403, { error: 'Forbidden', code: 'TRUSTED_PARTICIPANT_REQUIRED', message: msg })))
      .toMatchObject({ kind: 'trusted_required', message: msg });
    expect(parseApiError(res(403, { error: 'Forbidden', code: 'TRUSTED_PARTICIPANT_REQUIRED' })).message)
      .toBe('Для участия в торгах нужен статус доверенного пользователя.');
  });

  it('403 with a Russian business reason keeps it (trusted gate)', () => {
    const msg = 'Участие в торгах доступно только доверенным пользователям. Обратитесь к администратору для получения доступа.';
    expect(parseApiError(res(403, { error: 'Forbidden', message: msg }))).toMatchObject({ kind: 'forbidden', message: msg });
  });

  it('English Spring Security texts are replaced by Russian defaults (401/403)', () => {
    expect(parseApiError(res(401, { error: 'Unauthorized', message: 'Invalid username or password' })).message).toBe('Требуется вход в систему.');
    expect(parseApiError(res(403, { error: 'Forbidden', message: "You don't have permission to access this resource" })).message)
      .toBe('Недостаточно прав для этого действия.');
  });

  it('404, 409, 422, 429 and 500 map to their kinds; Russian backend message wins', () => {
    expect(parseApiError(res(404, { message: 'Заявка не найдена: 5' }))).toMatchObject({ kind: 'not_found', message: 'Заявка не найдена: 5' });
    expect(parseApiError(res(409, { code: 'OPTIMISTIC_VERSION_CONFLICT', message: 'Данные были изменены другим администратором.' })))
      .toMatchObject({ kind: 'conflict', code: 'OPTIMISTIC_VERSION_CONFLICT' });
    expect(parseApiError(res(422, { message: 'Недостаточно средств', available: 0, required: 10 })).kind).toBe('insufficient_funds');
    expect(parseApiError(res(429, { message: 'Слишком много ставок. Пожалуйста, подождите.' })).kind).toBe('rate_limited');
    expect(parseApiError(res(500, { message: 'Произошла непредвиденная ошибка' }))).toMatchObject({ kind: 'server', message: 'Произошла непредвиденная ошибка' });
    expect(parseApiError(res(502, '<html>Bad Gateway</html>'))).toMatchObject({ kind: 'server', message: 'Сервер не смог выполнить запрос. Повторите попытку позже.' });
  });

  it('no response -> network; a plain Error -> unknown with the caller fallback', () => {
    expect(parseApiError({ isAxiosError: true, request: {}, message: 'Network Error' }).kind).toBe('network');
    expect(parseApiError(new Error('boom'), 'Ошибка при принятии заявки')).toMatchObject({ kind: 'unknown', message: 'Ошибка при принятии заявки' });
    expect(apiErrorMessage(undefined)).toBe('Не удалось выполнить действие.');
  });
});

describe('FE-ERR-01 adversarial: malformed and hostile bodies never break the parser or leak markup', () => {
  it('HTML or English bodies fall back to Russian defaults', () => {
    expect(parseApiError(res(400, { message: '<script>alert(1)</script> ошибка' })).message).toBe('Проверьте введённые данные.');
    expect(parseApiError(res(409, { message: 'Conflict' })).message).toBe('Действие сейчас недоступно: данные изменились. Обновите страницу.');
  });

  it('details that are not an object of strings are ignored', () => {
    for (const details of [null, 'x', 42, ['a'], { a: 1, b: null, c: '' }]) {
      const e = parseApiError(res(400, { details }));
      expect(e.fieldErrors).toEqual({});
      expect(e.message).toBe('Проверьте введённые данные.');
    }
  });

  it('non-object data, missing status, null error object', () => {
    expect(parseApiError(res(400, null)).kind).toBe('validation');
    expect(parseApiError(res(418, 'teapot')).kind).toBe('unknown');
    expect(parseApiError({ response: { data: {} } }).kind).toBe('unknown');
    expect(parseApiError(null).kind).toBe('unknown');
  });

  it('an over-long backend message is truncated', () => {
    const long = 'Ошибка '.repeat(200);
    expect(parseApiError(res(409, { message: long })).message.length).toBeLessThanOrEqual(501);
  });

  it('the KYC code is authoritative whatever the status; an unknown code does not change the kind', () => {
    expect(parseApiError(res(409, { code: 'IDENTITY_VERIFICATION_REQUIRED', message: 'Требуется KYC' })).kind).toBe('kyc_required');
    expect(parseApiError(res(400, { code: 'SOMETHING' })).kind).toBe('validation');
  });
});
