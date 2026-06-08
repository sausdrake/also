/**
 * Надёжный парсер ISO 8601 дат.
 * Обрабатывает форматы:
 *   - "2026-06-05T22:45:55.123456789+07:00"  (Java OffsetDateTime, наносекунды)
 *   - "2026-06-05T22:45:55.123+07:00"         (миллисекунды)
 *   - "2026-06-05T22:45:55+07:00"             (без дроби)
 *   - "2026-06-05T22:45:55Z"                   (UTC)
 *   - число (unix timestamp ms)
 *   - массив [2026, 6, 5, 22, 45, 55] (Jackson timestamp array)
 */
export function parseDate(raw: unknown): Date | null {
  if (raw == null) return null;

  if (typeof raw === 'number') {
    const d = new Date(raw < 1e10 ? raw * 1000 : raw);
    return isNaN(d.getTime()) ? null : d;
  }

  if (Array.isArray(raw)) {
    const [y, mo, d, h = 0, m = 0, s = 0] = raw as number[];
    const date = new Date(y, mo - 1, d, h, m, s);
    return isNaN(date.getTime()) ? null : date;
  }

  if (typeof raw !== 'string') return null;
  if (!raw.trim()) return null;

  // Обрезаем дробную часть до 3 цифр (миллисекунды)
  const normalized = raw.replace(/(\.\d{3})\d*/g, '$1');

  const d = new Date(normalized);
  return isNaN(d.getTime()) ? null : d;
}

/** Форматирует дату и время в локальном часовом поясе браузера. */
export function formatDateTime(raw: unknown): string {
  const d = parseDate(raw);
  if (!d) return '—';
  return d.toLocaleString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/** Форматирует только дату в локальном часовом поясе браузера. */
export function formatDate(raw: unknown): string {
  const d = parseDate(raw);
  if (!d) return '—';
  return d.toLocaleDateString('ru-RU', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
  });
}
