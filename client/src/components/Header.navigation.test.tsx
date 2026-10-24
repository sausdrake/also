import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { Header } from './Header';
import { useAuthStore } from '../store/authStore';

/**
 * G1: the site header's information architecture. Before: "Кабинет" (header), "Личный кабинет" and
 * "Депозит" (avatar menu) were three links to /profile, and below md the section row was hidden with
 * nothing in its place - an admin or a diagnostician on a phone could not get back to their area.
 * The md breakpoint itself is CSS (verified in the browser); here the mobile group is identified by
 * its `md:hidden` class.
 */

vi.mock('../hooks/useNotifications', () => ({
  useNotifications: () => ({
    notifications: [], unreadCount: 0, status: 'ready', loadError: '', actionError: '',
    markRead: vi.fn(), markAllRead: vi.fn(), reload: vi.fn(),
  }),
}));

const logout = vi.fn();

function signIn(roles: string[] | null, username = 'demo_user') {
  useAuthStore.setState(roles
    ? { isAuthenticated: true, accessToken: 'x', user: { id: 1, username, email: null, roles }, logout } as never
    : { isAuthenticated: false, accessToken: null, user: null, logout } as never);
}

function renderAt(path = '/') {
  const page = (text: string) => <><Header /><p>{text}</p></>;
  const router = createMemoryRouter([
    { path: '/', element: page('HOME') },
    { path: '/profile/*', element: page('PROFILE') },
    { path: '/admin/*', element: page('ADMIN') },
    { path: '/diagnostic/*', element: page('DIAG') },
    { path: '/auction/:id', element: page('LOT') },
    { path: '/login', element: <p>LOGIN</p> },
  ], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
  return router;
}

const sections = () => screen.getByRole('navigation', { name: 'Разделы' });
const avatar = () => screen.getByRole('button', { name: /^Меню пользователя/ });
const openMenu = () => { fireEvent.click(avatar()); return document.getElementById('account-menu')!; };
const linkPairs = (el: HTMLElement) => within(el).getAllByRole('link').map(a => [a.textContent, a.getAttribute('href')]);

beforeEach(() => { logout.mockReset(); });

describe('G1: header sections by role', () => {
  it.each([
    [['ROLE_USER'], [['Аукционы', '/'], ['Кабинет', '/profile']]],
    [['ROLE_DIAGNOSTIC'], [['Аукционы', '/'], ['Диагностика', '/diagnostic']]],
    [['ROLE_ADMIN'], [['Аукционы', '/'], ['Кабинет', '/profile'], ['Админ', '/admin']]],
  ])('%j sees exactly %j', (roles, expected) => {
    signIn(roles);
    renderAt();
    expect(linkPairs(sections())).toEqual(expected);
  });

  it('anonymous: no sections and no account menu, only Войти / Регистрация', () => {
    signIn(null);
    renderAt();
    expect(screen.queryByRole('navigation', { name: 'Разделы' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^Меню пользователя/ })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Войти' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: 'Регистрация' })).toHaveAttribute('href', '/register');
  });

  it('the current section is marked (aria-current), "Аукционы" only on the catalogue itself', () => {
    signIn(['ROLE_USER']);
    renderAt('/profile/bids');
    expect(within(sections()).getByRole('link', { name: 'Кабинет' })).toHaveAttribute('aria-current', 'page');
    expect(within(sections()).getByRole('link', { name: 'Аукционы' })).not.toHaveAttribute('aria-current');
  });

  it('the row is the wide-screen variant (hidden below md)', () => {
    signIn(['ROLE_USER']);
    renderAt();
    expect(sections().className.split(' ')).toEqual(expect.arrayContaining(['hidden', 'md:flex']));
  });
});

describe('G1: the account menu holds the person, not the sections', () => {
  it.each([
    [['ROLE_USER'], [['Профиль', '/profile/settings'], ['Документы и данные', '/profile/privacy']]],
    [['ROLE_DIAGNOSTIC'], [['Профиль', '/profile'], ['Документы и данные', '/profile/privacy']]],
    [['ROLE_ADMIN'], [['Профиль', '/profile/settings'], ['Документы и данные', '/profile/privacy']]],
  ])('%j: %j, then Выйти', (roles, expected) => {
    signIn(roles);
    renderAt();
    const menu = openMenu();
    expect(linkPairs(within(menu).getByRole('navigation', { name: 'Аккаунт' }))).toEqual(expected);
    expect(within(menu).getByRole('button', { name: 'Выйти' })).toBeInTheDocument();
  });

  it('no duplicates: on a wide screen the menu repeats no section and no link twice', () => {
    signIn(['ROLE_ADMIN']);
    renderAt();
    const menu = openMenu();
    const account = linkPairs(within(menu).getByRole('navigation', { name: 'Аккаунт' })).map(p => p[1]);
    const header = linkPairs(sections()).map(p => p[1]);
    expect(account.filter(h => header.includes(h))).toEqual([]);
    expect(new Set(account).size).toBe(account.length);
    expect(within(menu).queryByRole('link', { name: 'Депозит' })).not.toBeInTheDocument();
    expect(within(menu).queryByRole('link', { name: 'Личный кабинет' })).not.toBeInTheDocument();
  });

  it.each([[['ROLE_USER']], [['ROLE_DIAGNOSTIC']], [['ROLE_ADMIN']]])(
    '%j on a phone: the menu starts with the same sections as the header (md:hidden group)', roles => {
      signIn(roles);
      renderAt();
      const header = linkPairs(sections());
      const menu = openMenu();
      const group = screen.getByTestId('account-menu-sections');
      expect(group).toBe(within(menu).getByRole('navigation', { name: 'Разделы (меню)' }));
      expect(group.className.split(' ')).toContain('md:hidden');
      expect(group.className.split(' ')).not.toContain('hidden');
      expect(linkPairs(group)).toEqual(header);
    });

  it('the admin on a phone reaches the admin area from the menu (it used to be unreachable)', () => {
    signIn(['ROLE_ADMIN']);
    const router = renderAt('/auction/5');
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Админ' }));
    expect(router.state.location.pathname).toBe('/admin');
    expect(screen.getByText('ADMIN')).toBeInTheDocument();
  });
});

describe('G1: account menu behaviour', () => {
  it('the button has an accessible name and reports its state', () => {
    signIn(['ROLE_USER'], 'demo_buyer');
    renderAt();
    expect(avatar()).toHaveAccessibleName('Меню пользователя demo_buyer');
    expect(avatar()).toHaveAttribute('aria-expanded', 'false');
    expect(avatar()).toHaveAttribute('aria-controls', 'account-menu');
    openMenu();
    expect(avatar()).toHaveAttribute('aria-expanded', 'true');
  });

  it('closes on Escape, on a click outside and on a second click', () => {
    signIn(['ROLE_USER']);
    renderAt();
    openMenu();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(document.getElementById('account-menu')).toBeNull();
    openMenu();
    fireEvent.mouseDown(screen.getByText('HOME'));
    expect(document.getElementById('account-menu')).toBeNull();
    openMenu();
    fireEvent.click(avatar());
    expect(document.getElementById('account-menu')).toBeNull();
  });

  it('a click inside the menu does not close it before the link works', () => {
    signIn(['ROLE_USER']);
    renderAt();
    const menu = openMenu();
    fireEvent.mouseDown(within(menu).getByRole('link', { name: 'Профиль' }));
    expect(document.getElementById('account-menu')).not.toBeNull();
  });

  it('closes when the route changes - by a menu link and by browser Back', async () => {
    signIn(['ROLE_USER']);
    const router = renderAt('/');
    fireEvent.click(within(openMenu()).getByRole('link', { name: 'Профиль' }));
    expect(router.state.location.pathname).toBe('/profile/settings');
    expect(document.getElementById('account-menu')).toBeNull();
    openMenu();
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/');
    expect(document.getElementById('account-menu')).toBeNull();
  });

  it('rapid open/close leaves one consistent state', () => {
    signIn(['ROLE_USER']);
    renderAt();
    for (let i = 0; i < 7; i++) fireEvent.click(avatar());
    expect(avatar()).toHaveAttribute('aria-expanded', 'true');
    expect(document.querySelectorAll('#account-menu')).toHaveLength(1);
  });

  it('Выйти signs out and then opens the login page by replace (NAV-BACK-LOGIN)', async () => {
    signIn(['ROLE_USER']);
    const router = renderAt();
    fireEvent.click(within(openMenu()).getByRole('button', { name: 'Выйти' }));
    expect(logout).toHaveBeenCalledTimes(1);
    await act(async () => {});
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('touch targets: menu entries are at least 44px tall (min-h-11)', () => {
    signIn(['ROLE_USER']);
    renderAt();
    const menu = openMenu();
    for (const el of [...within(menu).getAllByRole('link'), within(menu).getByRole('button', { name: 'Выйти' })]) {
      expect(el.className.split(' ')).toContain('min-h-11');
    }
  });
});
