/**
 * AUCTION-01: format of a money amount the backend accepts for prices and bids - som with at most two
 * decimals (tyiyn), positive, within the technical capacity of the NUMERIC(10,2) columns. This is a
 * capacity bound, not a business maximum (BD-05: none is defined). The backend stays authoritative;
 * this only avoids sending a request it will certainly refuse. No bid-step formula lives here: the
 * minimum bid step and the minimum next bid always come from the server.
 */
export const MONEY_CAPACITY = 99_999_999.99;

const MONEY = /^\d+(?:[.,]\d{1,2})?$/;
const DECIMAL = /^\d+(?:[.,]\d+)?$/;

/** The amount as the backend will receive it, or null when the input is not a plain decimal number. */
export function toMoney(raw: string): number | null {
  const t = raw.trim();
  if (!DECIMAL.test(t)) return null;
  const n = Number(t.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

/** Why the input is not a valid money amount, or null when it is. */
export function moneyInputError(raw: string): string | null {
  const t = raw.trim();
  const n = toMoney(t);
  if (n === null) return 'Введите сумму числом';
  if (n <= 0) return 'Сумма должна быть больше 0';
  if (!MONEY.test(t)) return 'Не больше 2 знаков после запятой';
  if (n > MONEY_CAPACITY) return 'Максимум: 99 999 999.99 сом';
  return null;
}

/** Som with up to `fractionDigits` decimals, ru grouping. */
export const formatSom = (n: number | null | undefined, fractionDigits = 2) =>
  (n ?? 0).toLocaleString('ru', { maximumFractionDigits: fractionDigits });
