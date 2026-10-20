import { describe, expect, it } from 'vitest';
import { WITHDRAWAL_OWNER_LABELS, WITHDRAWAL_STATUSES, toWithdrawalAmount, validateWithdrawalAmount } from './withdrawal';

/** FE-WD-01: the client check mirrors backend WithdrawRequest (@DecimalMin 0.01, @Digits fraction 2) + the shown balance. */
describe('FE-WD-01: withdrawal amount validation', () => {
  it.each([
    ['1500', 2000], ['1500.50', 2000], ['1500,5', 2000], ['0.01', 1], ['2000', 2000], [' 300 ', 2000],
  ])('accepts %s (available %d)', (raw, available) => {
    expect(validateWithdrawalAmount(raw, available)).toBeNull();
  });

  it.each([
    ['', 'Введите сумму'], ['   ', 'Введите сумму'],
    ['abc', 'Введите сумму числом, например 1500 или 1500.50'], ['1e3', 'Введите сумму числом, например 1500 или 1500.50'],
    ['-5', 'Введите сумму числом, например 1500 или 1500.50'], ['12abc', 'Введите сумму числом, например 1500 или 1500.50'],
    ['1.2.3', 'Введите сумму числом, например 1500 или 1500.50'], ['Infinity', 'Введите сумму числом, например 1500 или 1500.50'],
    ['0', 'Сумма должна быть больше 0'], ['0.00', 'Сумма должна быть больше 0'],
    ['0.001', 'Не больше двух знаков после запятой'], ['100.555', 'Не больше двух знаков после запятой'],
  ])('rejects %j', (raw, message) => {
    expect(validateWithdrawalAmount(raw, 1_000_000)).toBe(message);
  });

  it('above the available balance, or while it is unknown, nothing is sent', () => {
    expect(validateWithdrawalAmount('2000.01', 2000)).toMatch(/^Максимум: 2\s000 сом$/);
    expect(validateWithdrawalAmount('100', null)).toBe('Доступный баланс ещё не загружен');
  });

  it('toWithdrawalAmount normalises a comma and refuses anything that is not a plain decimal', () => {
    expect(toWithdrawalAmount('10,5')).toBe(10.5);
    expect(toWithdrawalAmount('250')).toBe(250);
    expect(toWithdrawalAmount('1e3')).toBeNull();
    expect(toWithdrawalAmount('')).toBeNull();
  });

  it('owner labels cover every backend status', () => {
    expect(Object.keys(WITHDRAWAL_OWNER_LABELS).sort()).toEqual([...WITHDRAWAL_STATUSES].sort());
  });
});
