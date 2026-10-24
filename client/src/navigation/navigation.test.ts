import { describe, expect, it } from 'vitest';
import {
  ADMIN_NAV, accountNav, hasRole, primaryNav, profilePath, profileTabs, roleAreaAccountNav, safeReturnPath,
} from './navigation';

/** G1: the navigation rules - which sections a role sees, where the account lives, safe sign-in return. */

const USER = ['ROLE_USER'];
const DIAG = ['ROLE_DIAGNOSTIC'];
const ADMIN = ['ROLE_ADMIN'];
const labels = (items: { label: string }[]) => items.map(i => i.label);
const paths = (items: { to: string }[]) => items.map(i => i.to);

describe('G1: roles', () => {
  it.each([
    [['ROLE_ADMIN'], 'ADMIN', true], [['ADMIN'], 'ADMIN', true], [['ROLE_USER'], 'ADMIN', false],
    [['ROLE_ADMINISTRATOR'], 'ADMIN', false], [[], 'USER', false], [undefined, 'USER', false], [null, 'DIAGNOSTIC', false],
  ] as const)('hasRole(%j, %s) = %s', (roles, role, expected) => {
    expect(hasRole(roles as never, role)).toBe(expected);
  });
});

describe('G1: primary navigation by role (= the route guards)', () => {
  it('anonymous / unknown roles: only the catalogue and the profile (the guard asks to sign in)', () => {
    expect(primaryNav(undefined)).toEqual([{ to: '/', label: 'Аукционы', end: true }, { to: '/profile', label: 'Кабинет' }]);
  });

  it('USER (seller and buyer are the same role): Аукционы, Кабинет - no role areas', () => {
    expect(primaryNav(USER)).toEqual([{ to: '/', label: 'Аукционы', end: true }, { to: '/profile', label: 'Кабинет' }]);
  });

  it('DIAGNOSTIC: Аукционы, Диагностика - no "Кабинет" (its profile has no money tabs), no Админ', () => {
    expect(paths(primaryNav(DIAG))).toEqual(['/', '/diagnostic']);
    expect(labels(primaryNav(DIAG))).toEqual(['Аукционы', 'Диагностика']);
  });

  it('ADMIN: Аукционы, Кабинет, Админ - not the diagnostician area link', () => {
    expect(primaryNav(ADMIN)).toEqual([
      { to: '/', label: 'Аукционы', end: true }, { to: '/profile', label: 'Кабинет' }, { to: '/admin', label: 'Админ' },
    ]);
  });

  it('short role names from the token work the same', () => {
    expect(primaryNav(['ADMIN'])).toEqual(primaryNav(ADMIN));
    expect(primaryNav(['DIAGNOSTIC'])).toEqual(primaryNav(DIAG));
  });

  it.each([[USER], [DIAG], [ADMIN], [['ROLE_ADMIN', 'ROLE_DIAGNOSTIC']]])('%j: no section is listed twice', roles => {
    const p = paths(primaryNav(roles));
    expect(new Set(p).size).toBe(p.length);
  });
});

describe('G1: the account menu does not repeat the sections (NAV-INV-01/02)', () => {
  it.each([[USER], [DIAG], [ADMIN]])('%j: Профиль and Документы и данные, none of them a section', roles => {
    const account = accountNav(roles);
    expect(labels(account)).toEqual(['Профиль', 'Документы и данные']);
    for (const a of account) expect(paths(primaryNav(roles))).not.toContain(a.to);
  });

  it('Профиль is the "Профиль" tab of the role\'s own profile', () => {
    expect(profilePath(USER)).toBe('/profile/settings');
    expect(profilePath(ADMIN)).toBe('/profile/settings');
    expect(profilePath(DIAG)).toBe('/profile');
    for (const roles of [USER, DIAG, ADMIN]) {
      expect(accountNav(roles)[0].to).toBe(profilePath(roles));
      expect(accountNav(roles)[1].to).toBe('/profile/privacy');
    }
  });

  it('every account link is a real tab of that role\'s profile', () => {
    for (const roles of [USER, DIAG, ADMIN]) {
      const hrefs = profileTabs(roles).map(t => (t.path ? `/profile/${t.path}` : '/profile'));
      for (const a of accountNav(roles)) expect(hrefs).toContain(a.to);
    }
  });

  it('role areas: back to the site and the profile', () => {
    expect(roleAreaAccountNav(DIAG)).toEqual([{ to: '/', label: 'На сайт', end: true }, { to: '/profile', label: 'Профиль' }]);
    expect(roleAreaAccountNav(ADMIN)).toEqual([{ to: '/', label: 'На сайт', end: true }, { to: '/profile/settings', label: 'Профиль' }]);
  });
});

describe('G1: profile tabs', () => {
  it('USER keeps all eight tabs in their order (nothing removed)', () => {
    expect(profileTabs(USER)).toEqual([
      { path: '', label: 'Депозит' }, { path: 'bids', label: 'Участвовал' }, { path: 'won', label: 'Выигранные' },
      { path: 'requests', label: 'Заявки' }, { path: 'watched', label: 'Отслеживаемые' },
      { path: 'identity', label: 'Подтверждение личности' }, { path: 'privacy', label: 'Документы и данные' },
      { path: 'settings', label: 'Профиль' },
    ]);
  });

  it('DIAGNOSTIC: the profile and the documents / personal-data requests (backend: every signed-in person)', () => {
    expect(profileTabs(DIAG)).toEqual([{ path: '', label: 'Профиль' }, { path: 'privacy', label: 'Документы и данные' }]);
  });

  it('ADMIN: the USER tabs, unchanged (whether an admin needs them is a business question)', () => {
    expect(profileTabs(ADMIN)).toEqual(profileTabs(USER));
  });
});

describe('G1: admin sidebar', () => {
  const flat = ADMIN_NAV.flatMap(e => (e.children ? e.children : [e]));

  it('lists every admin page (the two orphans included) once', () => {
    expect(flat.map(e => e.to)).toEqual([
      '/admin', '/admin/users', '/admin/diagnostic/templates', '/admin/diagnostic/reports',
      '/admin/vehicles/needs-rediagnostic', '/admin/auctions', '/admin/policy', '/admin/deposits', '/admin/withdrawals',
      '/admin/payments/late-successes', '/admin/settings', '/admin/identity', '/admin/legal', '/admin/reconciliation',
    ]);
    expect(new Set(flat.map(e => e.label)).size).toBe(flat.length);
  });

  it('only the dashboard is an exact match (it is a prefix of every other page)', () => {
    expect(flat.filter(e => e.end).map(e => e.to)).toEqual(['/admin']);
  });
});

describe('G1: return after sign-in accepts only an in-app path (no open redirect)', () => {
  it.each([
    ['/profile/watched', '/profile/watched'],
    ['/auction/7', '/auction/7'],
    [{ pathname: '/admin/sale/3', search: '?tab=1', hash: '#pay' }, '/admin/sale/3?tab=1#pay'],
    [{ pathname: '/profile' }, '/profile'],
    ['/', '/'],
  ])('%j -> %s', (from, expected) => {
    expect(safeReturnPath(from)).toBe(expected);
  });

  it.each([
    ['https://evil.example/x'], ['//evil.example'], ['/\\evil.example'], ['\\\\evil'], ['javascript:alert(1)'],
    ['evil.example'], [''], ['/login'], ['/login?x=1'], ['/register'], ['/auth/telegram/callback'], ['/a\nb'], ['/a\u0000'],
    [{ pathname: '//evil.example' }], [{ pathname: 5 }], [{}], [null], [undefined], [42], [['/profile']],
  ])('%j -> null', from => {
    expect(safeReturnPath(from)).toBeNull();
  });

  it('a path that only starts like a sign-in page is still a page', () => {
    expect(safeReturnPath('/loginhelp')).toBe('/loginhelp');
    expect(safeReturnPath('/registered-lots')).toBe('/registered-lots');
  });
});
