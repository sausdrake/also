import { describe, expect, it } from 'vitest';
import { parseDate, formatDateTime, formatDate } from './formatDate';

describe('parseDate', () => {
  it('parses a Java OffsetDateTime string with nanosecond fractional seconds', () => {
    const d = parseDate('2026-06-05T22:45:55.123456789+07:00');
    expect(d).not.toBeNull();
    expect(d!.getUTCFullYear()).toBe(2026);
    expect(d!.getUTCMonth()).toBe(5); // 0-indexed -> June
    expect(d!.getUTCDate()).toBe(5);
    expect(d!.getUTCHours()).toBe(15); // 22:45 +07:00 -> 15:45 UTC
    expect(d!.getUTCMinutes()).toBe(45);
    expect(d!.getUTCSeconds()).toBe(55);
    expect(d!.getUTCMilliseconds()).toBe(123);
  });

  it('parses a string with millisecond fractional seconds', () => {
    const d = parseDate('2026-06-05T22:45:55.123+07:00');
    expect(d).not.toBeNull();
    expect(d!.getUTCMilliseconds()).toBe(123);
  });

  it('parses a string with no fractional seconds', () => {
    const d = parseDate('2026-06-05T22:45:55+07:00');
    expect(d).not.toBeNull();
    expect(d!.getUTCSeconds()).toBe(55);
    expect(d!.getUTCMilliseconds()).toBe(0);
  });

  it('parses a UTC "Z" string', () => {
    const d = parseDate('2026-06-05T22:45:55Z');
    expect(d).not.toBeNull();
    expect(d!.getUTCHours()).toBe(22);
    expect(d!.getUTCMinutes()).toBe(45);
  });

  it('parses a unix timestamp in milliseconds (large number)', () => {
    const ms = Date.UTC(2026, 5, 5, 22, 45, 55);
    const d = parseDate(ms);
    expect(d).not.toBeNull();
    expect(d!.getTime()).toBe(ms);
  });

  it('parses a unix timestamp in seconds (small number, below the 1e10 threshold) by converting to milliseconds', () => {
    const seconds = Math.floor(Date.UTC(2026, 5, 5, 22, 45, 55) / 1000);
    const d = parseDate(seconds);
    expect(d).not.toBeNull();
    expect(d!.getTime()).toBe(seconds * 1000);
  });

  it('parses a Jackson timestamp array [year, month, day, hour, minute, second]', () => {
    const d = parseDate([2026, 6, 5, 22, 45, 55]);
    expect(d).not.toBeNull();
    // Array form is interpreted in local time (new Date(y, mo-1, d, h, m, s)), month is 1-indexed in the input.
    expect(d!.getFullYear()).toBe(2026);
    expect(d!.getMonth()).toBe(5);
    expect(d!.getDate()).toBe(5);
    expect(d!.getHours()).toBe(22);
    expect(d!.getMinutes()).toBe(45);
    expect(d!.getSeconds()).toBe(55);
  });

  it('parses a Jackson timestamp array with omitted time components, defaulting them to zero', () => {
    const d = parseDate([2026, 6, 5]);
    expect(d).not.toBeNull();
    expect(d!.getHours()).toBe(0);
    expect(d!.getMinutes()).toBe(0);
    expect(d!.getSeconds()).toBe(0);
  });

  it('returns null for null', () => {
    expect(parseDate(null)).toBeNull();
  });

  it('returns null for undefined', () => {
    expect(parseDate(undefined)).toBeNull();
  });

  it('returns null for an empty string', () => {
    expect(parseDate('')).toBeNull();
  });

  it('returns null for a whitespace-only string', () => {
    expect(parseDate('   ')).toBeNull();
  });

  it('returns null for an unparseable string', () => {
    expect(parseDate('not-a-date')).toBeNull();
  });

  it('returns null for a value of an unsupported type (object)', () => {
    expect(parseDate({})).toBeNull();
  });

  it('returns null for a NaN unix timestamp', () => {
    expect(parseDate(NaN)).toBeNull();
  });
});

describe('formatDateTime', () => {
  it('formats a valid date as day.month.year, hour:minute in ru-RU locale', () => {
    const result = formatDateTime('2026-06-05T22:45:55Z');
    // ru-RU DD.MM.YYYY, HH:MM - assert on the actual locale-formatted value rather than
    // hand-rolling a parallel implementation.
    const expected = new Date('2026-06-05T22:45:55Z').toLocaleString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
    expect(result).toBe(expected);
  });

  it('returns an em dash for an unparseable value', () => {
    expect(formatDateTime('garbage')).toBe('—');
  });

  it('returns an em dash for null', () => {
    expect(formatDateTime(null)).toBe('—');
  });
});

describe('formatDate', () => {
  it('formats a valid date as day.month.year in ru-RU locale, with no time component', () => {
    const result = formatDate('2026-06-05T22:45:55Z');
    const expected = new Date('2026-06-05T22:45:55Z').toLocaleDateString('ru-RU', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
    });
    expect(result).toBe(expected);
  });

  it('returns an em dash for an unparseable value', () => {
    expect(formatDate('garbage')).toBe('—');
  });
});
