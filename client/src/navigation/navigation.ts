/**
 * G1: the app's navigation in one place - which sections a role sees and where its account lives.
 * Visibility only: hiding an item is never access control (the route guards and the backend decide).
 * Roles are the backend names (ROLE_USER / ROLE_DIAGNOSTIC / ROLE_ADMIN); a seller and a buyer are the
 * same ROLE_USER, so there is no seller/buyer split here.
 */

export interface NavItem {
  to: string;
  label: string;
  /** Active only on this exact path (for a section root that is a prefix of others). */
  end?: boolean;
}

export interface ProfileTab {
  /** Relative to /profile; '' is the index tab. */
  path: string;
  label: string;
}

/** Accepts "ROLE_ADMIN" or the short "ADMIN" (JWT claims are not uniform). */
export function hasRole(roles: readonly string[] | null | undefined, role: 'USER' | 'DIAGNOSTIC' | 'ADMIN'): boolean {
  return (roles ?? []).some(r => r === role || r === `ROLE_${role}`);
}

/**
 * A diagnostician's profile has no money tabs (the work happens in /diagnostic). Decided by the
 * DIAGNOSTIC role, as the profile page always did.
 */
export const isDiagnosticProfile = (roles: readonly string[] | null | undefined) => hasRole(roles, 'DIAGNOSTIC');

const USER_TABS: ProfileTab[] = [
  { path: '', label: 'Депозит' },
  { path: 'bids', label: 'Участвовал' },
  { path: 'won', label: 'Выигранные' },
  { path: 'requests', label: 'Заявки' },
  { path: 'watched', label: 'Отслеживаемые' },
  { path: 'identity', label: 'Подтверждение личности' },
  { path: 'privacy', label: 'Документы и данные' },
  { path: 'settings', label: 'Профиль' },
];

// The documents and personal-data requests are every signed-in person's (backend: authenticated).
const DIAGNOSTIC_TABS: ProfileTab[] = [
  { path: '', label: 'Профиль' },
  { path: 'privacy', label: 'Документы и данные' },
];

export function profileTabs(roles: readonly string[] | null | undefined): ProfileTab[] {
  return isDiagnosticProfile(roles) ? DIAGNOSTIC_TABS : USER_TABS;
}

const tabHref = (t: ProfileTab) => (t.path === '' ? '/profile' : `/profile/${t.path}`);

/** The person's own profile page (name, Telegram, password) - the "Профиль" tab of their profile. */
export function profilePath(roles: readonly string[] | null | undefined): string {
  return tabHref(profileTabs(roles).find(t => t.label === 'Профиль')!);
}

/** Sections: the header on a wide screen, the "Разделы" group of the account menu on a phone. */
export function primaryNav(roles: readonly string[] | null | undefined): NavItem[] {
  const items: NavItem[] = [{ to: '/', label: 'Аукционы', end: true }];
  if (!isDiagnosticProfile(roles)) items.push({ to: '/profile', label: 'Кабинет' });
  if (hasRole(roles, 'DIAGNOSTIC')) items.push({ to: '/diagnostic', label: 'Диагностика' });
  if (hasRole(roles, 'ADMIN')) items.push({ to: '/admin', label: 'Админ' });
  return items;
}

/** The account menu: the person, not the sections. */
export function accountNav(roles: readonly string[] | null | undefined): NavItem[] {
  return [
    { to: profilePath(roles), label: 'Профиль' },
    { to: '/profile/privacy', label: 'Документы и данные' },
  ];
}

/** Bottom of a role area's sidebar (/diagnostic, /admin): back to the public site and the person's profile. */
export function roleAreaAccountNav(roles: readonly string[] | null | undefined): NavItem[] {
  return [
    { to: '/', label: 'На сайт', end: true },
    { to: profilePath(roles), label: 'Профиль' },
  ];
}

export interface AdminNavEntry extends NavItem {
  /** Sub-items of a collapsible group; a group itself is not a link. */
  children?: NavItem[];
}

/** The admin sidebar, top to bottom. Every admin page except the per-sale page is listed here. */
export const ADMIN_NAV: AdminNavEntry[] = [
  { to: '/admin', label: 'Dashboard', end: true },
  { to: '/admin/users', label: 'Пользователи' },
  {
    to: '/admin/diagnostic', label: 'Диагностика', children: [
      { to: '/admin/diagnostic/templates', label: 'Шаблоны' },
      { to: '/admin/diagnostic/reports', label: 'Отчёты' },
      { to: '/admin/vehicles/needs-rediagnostic', label: 'Повторная диагностика' },
    ],
  },
  { to: '/admin/auctions', label: 'Аукционы' },
  { to: '/admin/policy', label: 'Политика' },
  { to: '/admin/deposits', label: 'Депозиты' },
  { to: '/admin/withdrawals', label: 'Заявки на вывод' },
  { to: '/admin/payments/late-successes', label: 'Поздние платежи' },
  { to: '/admin/settings', label: 'Настройки' },
  { to: '/admin/identity', label: 'Личность' },
  { to: '/admin/legal', label: 'Документы и данные' },
  { to: '/admin/reconciliation', label: 'Сверка платежей' },
];

/** Paths a sign-in must never return to: the sign-in pages themselves. */
const NO_RETURN = /^\/(login|register|auth)(\/|$|\?|#)/;

/**
 * Where to go after signing in when a protected page sent the person to the login form: only a path
 * inside this app (no other origin, no protocol, no protocol-relative `//host` or `/\host`, no control
 * characters), never back to a sign-in page. Anything else -> null (the caller uses the role home).
 */
export function safeReturnPath(from: unknown): string | null {
  let path: unknown = from;
  if (from && typeof from === 'object') {
    const l = from as { pathname?: unknown; search?: unknown; hash?: unknown };
    if (typeof l.pathname !== 'string') return null;
    path = l.pathname + (typeof l.search === 'string' ? l.search : '') + (typeof l.hash === 'string' ? l.hash : '');
  }
  if (typeof path !== 'string' || !path.startsWith('/')) return null;
  if (path.startsWith('//') || path.includes('\\')) return null;
  if ([...path].some(c => c.charCodeAt(0) < 0x20 || c.charCodeAt(0) === 0x7f)) return null;
  if (NO_RETURN.test(path)) return null;
  return path;
}
