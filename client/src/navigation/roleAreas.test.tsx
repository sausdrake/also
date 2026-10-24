import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from '../pages/AdminDashboard';
import { DiagnosticDashboard } from '../pages/DiagnosticDashboard';
import { ProfilePage } from '../pages/ProfilePage';
import { RoleAreaFooter } from '../components/layout/RoleAreaFooter';
import { ADMIN_NAV } from './navigation';
import { useAuthStore } from '../store/authStore';

/**
 * G1: the role areas (/admin, /diagnostic) and the profile. Before: two admin pages had no link at
 * all, neither area had a way back to the site, to the person's profile or to the inbox (the site
 * header is hidden there), and an unknown child path rendered an empty page.
 */

vi.mock('../api/client', () => ({
  // lists are empty; the one single-object answer (the published policy) is "none yet"
  default: {
    get: vi.fn((url: string) => Promise.resolve({ data: url === '/admin/policy/current' ? null : [] })),
    post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn(),
  },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useNotifications', () => ({
  useNotifications: () => ({
    notifications: [], unreadCount: 2, status: 'ready', loadError: '', actionError: '',
    markRead: vi.fn(), markAllRead: vi.fn(), reload: vi.fn(),
  }),
}));

const logout = vi.fn(() => Promise.resolve());
const signIn = (roles: string[]) => useAuthStore.setState({
  accessToken: 'x', isAuthenticated: true, user: { id: 1, username: 'staff', email: null, roles }, logout,
} as never);

function renderAt(path: string) {
  const router = createMemoryRouter([
    { path: '/admin/*', element: <AdminDashboard /> },
    { path: '/diagnostic/*', element: <DiagnosticDashboard /> },
    { path: '/profile/*', element: <ProfilePage /> },
    { path: '/', element: <p>PUBLIC CATALOGUE</p> },
    { path: '/login', element: <p>LOGIN</p> },
  ], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

const sidebar = () => screen.getAllByRole('navigation', { name: 'Разделы' })[0];
const footer = () => screen.getAllByRole('navigation', { name: 'Аккаунт' })[0];

beforeEach(() => { logout.mockClear(); });

describe('G1: admin sidebar', () => {
  beforeEach(() => signIn(['ROLE_ADMIN']));

  it('shows every ADMIN_NAV entry; the diagnostics group opens to its three pages', () => {
    renderAt('/admin');
    const top = ADMIN_NAV.filter(e => !e.children).map(e => e.to);
    expect(within(sidebar()).getAllByRole('link').map(a => a.getAttribute('href'))).toEqual(top);
    fireEvent.click(within(sidebar()).getByRole('button', { name: /Диагностика/ }));
    expect(within(sidebar()).getByRole('link', { name: 'Повторная диагностика' })).toHaveAttribute('href', '/admin/vehicles/needs-rediagnostic');
    expect(within(sidebar()).getByRole('link', { name: 'Шаблоны' })).toHaveAttribute('href', '/admin/diagnostic/templates');
    expect(within(sidebar()).getByRole('link', { name: 'Отчёты' })).toHaveAttribute('href', '/admin/diagnostic/reports');
  });

  it.each(ADMIN_NAV.flatMap(e => e.children ?? [e]).map(e => [e.label, e.to]))(
    '"%s" opens %s itself (a route exists - it is not bounced to the dashboard)', async (_label, to) => {
      const router = renderAt('/admin');
      await act(() => router.navigate(to));
      expect(router.state.location.pathname).toBe(to);
    });

  it('the former orphans are reachable by clicking: Документы и данные, Повторная диагностика', () => {
    const router = renderAt('/admin');
    fireEvent.click(within(sidebar()).getByRole('link', { name: 'Документы и данные' }));
    expect(router.state.location.pathname).toBe('/admin/legal');
    fireEvent.click(within(sidebar()).getByRole('button', { name: /Диагностика/ }));
    fireEvent.click(within(sidebar()).getByRole('link', { name: 'Повторная диагностика' }));
    expect(router.state.location.pathname).toBe('/admin/vehicles/needs-rediagnostic');
  });

  it('the group is open and marked when one of its pages is current', () => {
    renderAt('/admin/vehicles/needs-rediagnostic');
    const group = within(sidebar()).getByRole('button', { name: /Диагностика/ });
    expect(group).toHaveAttribute('aria-expanded', 'true');
    expect(within(sidebar()).getByRole('link', { name: 'Повторная диагностика' })).toHaveAttribute('aria-current', 'page');
  });

  it('only the current page is marked; "Dashboard" is not marked on its sub-pages', () => {
    renderAt('/admin/withdrawals');
    const current = within(sidebar()).getAllByRole('link').filter(a => a.getAttribute('aria-current') === 'page');
    expect(current.map(a => a.textContent)).toEqual(['Заявки на вывод']);
  });

  it('footer: На сайт, Профиль (the admin\'s own profile tab), Выйти', () => {
    const router = renderAt('/admin/users');
    expect(within(footer()).getAllByRole('link').map(a => [a.textContent, a.getAttribute('href')]))
      .toEqual([['На сайт', '/'], ['Профиль', '/profile/settings']]);
    fireEvent.click(within(footer()).getByRole('link', { name: 'На сайт' }));
    expect(router.state.location.pathname).toBe('/');
  });

  it('Выйти signs out once and opens the login page', async () => {
    const router = renderAt('/admin');
    await act(async () => { fireEvent.click(screen.getAllByRole('button', { name: 'Выйти' })[0]); });
    expect(logout).toHaveBeenCalledTimes(1);
    expect(router.state.location.pathname).toBe('/login');
  });

  it('the inbox is in the area\'s top bar', () => {
    renderAt('/admin');
    expect(screen.getByRole('button', { name: 'Уведомления, непрочитанных: 2' })).toBeInTheDocument();
  });

  it('an unknown admin path still goes to the dashboard', () => {
    const router = renderAt('/admin/no-such');
    expect(router.state.location.pathname).toBe('/admin');
  });
});

describe('G1: diagnostician area', () => {
  beforeEach(() => signIn(['ROLE_DIAGNOSTIC']));

  it('sections unchanged; footer: На сайт, Профиль; the inbox in the top bar', () => {
    renderAt('/diagnostic/dashboard');
    expect(within(sidebar()).getAllByRole('link').map(a => a.getAttribute('href')))
      .toEqual(['/diagnostic/dashboard', '/diagnostic/available', '/diagnostic/reports']);
    expect(within(footer()).getAllByRole('link').map(a => [a.textContent, a.getAttribute('href')]))
      .toEqual([['На сайт', '/'], ['Профиль', '/profile']]);
    expect(screen.getByRole('button', { name: 'Уведомления, непрочитанных: 2' })).toBeInTheDocument();
  });

  it.each(['/diagnostic/no-such', '/diagnostic/reports/extra', '/diagnostic'])('%s -> the dashboard, not an empty page', path => {
    const router = renderAt(path);
    expect(router.state.location.pathname).toBe('/diagnostic/dashboard');
  });

  it('the footer link to the profile closes the drawer and opens the profile', () => {
    const router = renderAt('/diagnostic/available');
    fireEvent.click(screen.getByRole('button', { name: 'Открыть меню' }));
    const drawer = screen.getByRole('dialog', { name: 'Меню: Диагностика' });
    fireEvent.click(within(drawer).getByRole('link', { name: 'Профиль' }));
    expect(router.state.location.pathname).toBe('/profile');
    expect(screen.queryByRole('dialog', { name: 'Меню: Диагностика' })).not.toBeInTheDocument();
  });
});

describe('G1: role area footer contract', () => {
  // Today both footer links leave the area (the drawer unmounts anyway); the SidebarLayout contract
  // still asks every link to call onNavigate, so a future in-area link closes the drawer too.
  it.each(['На сайт', 'Профиль'])('"%s" calls onNavigate', label => {
    signIn(['ROLE_ADMIN']);
    const onNavigate = vi.fn();
    const router = createMemoryRouter([{ path: '*', element: <RoleAreaFooter onNavigate={onNavigate} /> }],
      { initialEntries: ['/admin/users'] });
    render(<RouterProvider router={router} />);
    fireEvent.click(screen.getByRole('link', { name: label }));
    expect(onNavigate).toHaveBeenCalledTimes(1);
  });
});

describe('G1: profile tabs and unknown tabs', () => {
  const tabs = () => screen.getByRole('navigation', { name: 'Вкладки кабинета' });

  it.each(['/profile/no-such-tab', '/profile/deposits', '/profile/withdrawals', '/profile/bids/extra'])(
    'USER: %s -> /profile (the deposit tab), not a page without content', path => {
      signIn(['ROLE_USER']);
      const router = renderAt(path);
      expect(router.state.location.pathname).toBe('/profile');
    });

  it.each(['bids', 'won', 'requests', 'watched', 'identity', 'privacy', 'settings'])('USER: /profile/%s is a real tab (stays)', tab => {
    signIn(['ROLE_USER']);
    const router = renderAt(`/profile/${tab}`);
    expect(router.state.location.pathname).toBe(`/profile/${tab}`);
    expect(within(tabs()).getAllByRole('link')).toHaveLength(8);
  });

  it('DIAGNOSTIC: Профиль and Документы и данные; the USER tabs lead back to the profile', () => {
    signIn(['ROLE_DIAGNOSTIC']);
    const router = renderAt('/profile/privacy');
    expect(router.state.location.pathname).toBe('/profile/privacy');
    expect(within(tabs()).getAllByRole('link').map(a => [a.textContent, a.getAttribute('href')]))
      .toEqual([['Профиль', '/profile'], ['Документы и данные', '/profile/privacy']]);
  });

  it.each(['/profile/bids', '/profile/won', '/profile/settings', '/profile/identity'])('DIAGNOSTIC: %s -> /profile', path => {
    signIn(['ROLE_DIAGNOSTIC']);
    const router = renderAt(path);
    expect(router.state.location.pathname).toBe('/profile');
  });
});
