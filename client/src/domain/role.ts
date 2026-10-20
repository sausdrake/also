/**
 * FE-UX-01: user roles as people read them. Source of truth: the backend role names (ROLE_USER,
 * ROLE_DIAGNOSTIC, ROLE_ADMIN - checked against the Java sources by role.contract.test.ts); the
 * wording is the one the admin role selector already uses. An unknown role shows its raw name.
 */
export const ROLES = ['ROLE_USER', 'ROLE_DIAGNOSTIC', 'ROLE_ADMIN'] as const;
export type Role = (typeof ROLES)[number];

export const ROLE_LABELS: Record<Role, string> = {
  ROLE_USER: 'Пользователь',
  ROLE_DIAGNOSTIC: 'Диагност',
  ROLE_ADMIN: 'Администратор',
};

/** Accepts "ROLE_ADMIN" or the short "ADMIN" (JWT claims are not uniform). */
export function roleLabel(raw: unknown): string {
  if (typeof raw !== 'string') return String(raw);
  const full = raw.startsWith('ROLE_') ? raw : `ROLE_${raw}`;
  return (ROLES as readonly string[]).includes(full) ? ROLE_LABELS[full as Role] : raw;
}
