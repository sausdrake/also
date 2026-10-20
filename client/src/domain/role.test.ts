import { describe, expect, it } from 'vitest';
import { ROLES, ROLE_LABELS, roleLabel } from './role';

/** FE-UX-01: role names as people read them; checked against the backend role literals. */
const javaSources = import.meta.glob('../../../src/main/java/**/*.java', { query: '?raw', import: 'default', eager: true }) as Record<string, string>;

describe('FE-UX-01: roles', () => {
  it('labels full and short role names; an unknown one stays raw', () => {
    expect(roleLabel('ROLE_ADMIN')).toBe('Администратор');
    expect(roleLabel('DIAGNOSTIC')).toBe('Диагност');
    expect(roleLabel('ROLE_USER')).toBe('Пользователь');
    expect(roleLabel('ROLE_AUDITOR')).toBe('ROLE_AUDITOR');
    expect(roleLabel('__proto__')).toBe('__proto__');
    expect(roleLabel(null)).toBe('null');
  });

  it('every ROLE_* literal in the backend sources has a label, and no label is for a role the backend lacks', () => {
    const backend = new Set(Object.values(javaSources).flatMap(src => [...src.matchAll(/"(ROLE_[A-Z_]+)"/g)].map(m => m[1])));
    expect([...backend].sort()).toEqual([...ROLES].sort());
    expect(Object.keys(ROLE_LABELS).sort()).toEqual([...ROLES].sort());
  });
});
