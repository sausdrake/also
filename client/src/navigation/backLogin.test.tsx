import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { Header } from '../components/Header';
import { RoleAreaFooter } from '../components/layout/RoleAreaFooter';
import { ProtectedRoute } from '../components/ProtectedRoute';
import { LoginPage } from '../pages/LoginPage';
import { ProfilePage } from '../pages/ProfilePage';
import { useAuthStore } from '../store/authStore';

/**
 * G1 NAV-BACK-LOGIN (found by the browser QA): signing out pushed a /login entry, and the login page showed
 * its form to a live session - so after "Выйти", a deep link and a sign-in, Back opened the login form for a
 * signed-in person. Now signing out replaces the entry, and /login sends a live session on (replace).
 * The real store's logout is used: it clears the session only after telling the backend.
 */

vi.mock('axios', () => ({ default: { post: vi.fn(), get: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { passwordSet: true } })), post: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useNotifications', () => ({
  useNotifications: () => ({
    notifications: [], unreadCount: 0, status: 'ready', loadError: '', actionError: '',
    markRead: vi.fn(), markAllRead: vi.fn(), reload: vi.fn(),
  }),
}));
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

function token(roles: string[], expInSec = 3600): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u', username: 'u', userId: 1, roles, exp: Math.floor(Date.now() / 1000) + expInSec })}.sig`;
}

const signInStore = (roles: string[], expInSec = 3600, accessToken = token(roles, expInSec)) => useAuthStore.setState({
  accessToken, refreshToken: 'r', isAuthenticated: true, user: { id: 1, username: 'u', email: null, roles },
} as never);

// the backend's /api/auth/logout answer, released by the test
let releaseLogout: () => void;
const fetchMock = vi.fn(() => new Promise<Response>(resolve => { releaseLogout = () => resolve(new Response(null, { status: 204 })); }));

const withHeader = (text: string) => <><Header /><p>{text}</p></>;

function app(initialEntries: (string | { pathname: string; state?: unknown })[], initialIndex?: number) {
  const router = createMemoryRouter([
    { path: '/login', element: <LoginPage /> },
    { path: '/', element: withHeader('HOME') },
    { path: '/profile/*', element: <ProtectedRoute>{withHeader('PROFILE AREA')}</ProtectedRoute> },
    { path: '/admin/*', element: <ProtectedRoute allowedRoles={['ADMIN']}><p>ADMIN AREA</p><RoleAreaFooter onNavigate={() => {}} /></ProtectedRoute> },
    { path: '/diagnostic/*', element: <ProtectedRoute allowedRoles={['DIAGNOSTIC', 'ADMIN']}><p>DIAG AREA</p><RoleAreaFooter onNavigate={() => {}} /></ProtectedRoute> },
    { path: '/forbidden', element: <p>FORBIDDEN</p> },
  ], { initialEntries, initialIndex });
  render(<RouterProvider router={router} />);
  return router;
}

const loginForm = () => screen.queryByPlaceholderText('Пароль');
const back = (router: ReturnType<typeof app>) => act(async () => { await router.navigate(-1); });

async function signOutFromHeader() {
  fireEvent.click(screen.getByRole('button', { name: /^Меню пользователя/ }));
  fireEvent.click(screen.getByRole('button', { name: 'Выйти' }));
  await act(async () => { releaseLogout(); });
}

async function submitLogin(roles: string[]) {
  axiosPost.mockResolvedValue({ data: { accessToken: token(roles), refreshToken: 'r' } });
  fireEvent.change(await screen.findByPlaceholderText('Имя пользователя'), { target: { value: 'u' } });
  fireEvent.change(screen.getByPlaceholderText('Пароль'), { target: { value: 'secret12' } });
  fireEvent.click(screen.getByRole('button', { name: /^Войти$/ }));
}

beforeEach(() => {
  axiosPost.mockReset();
  fetchMock.mockClear();
  vi.stubGlobal('fetch', fetchMock);
  localStorage.clear();
  useAuthStore.setState({ accessToken: null, refreshToken: null, isAuthenticated: false, user: null } as never);
});
afterEach(() => { vi.unstubAllGlobals(); });

describe('NAV-BACK-LOGIN: signing out leaves no /login entry', () => {
  it('header "Выйти": /login replaces the page, Back goes to the page before it', async () => {
    signInStore(['ROLE_USER']);
    const router = app(['/', '/profile/settings'], 1);
    await signOutFromHeader();
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.historyAction).toBe('REPLACE');
    expect(loginForm()).toBeInTheDocument();
    await back(router);
    expect(router.state.location.pathname).toBe('/');
  });

  it('the session is gone before /login opens (a live session there would be sent back to the site)', async () => {
    signInStore(['ROLE_USER']);
    const router = app(['/profile/settings']);
    fireEvent.click(screen.getByRole('button', { name: /^Меню пользователя/ }));
    fireEvent.click(screen.getByRole('button', { name: 'Выйти' }));
    expect(fetchMock).toHaveBeenCalledTimes(1);          // the backend is told first
    expect(router.state.location.pathname).toBe('/profile/settings');
    await act(async () => { releaseLogout(); });
    expect(useAuthStore.getState().accessToken).toBeNull();
    expect(router.state.location.pathname).toBe('/login');
    expect(loginForm()).toBeInTheDocument();
  });

  it.each([
    [['ROLE_ADMIN'], '/admin', '/admin/users'],
    [['ROLE_DIAGNOSTIC'], '/diagnostic', '/diagnostic/reports'],
  ])('%j area footer "Выйти": replace, Back to %s (the guard then asks to sign in)', async (roles, first, current) => {
    signInStore(roles);
    const router = app([first, current], 1);
    fireEvent.click(screen.getByRole('button', { name: 'Выйти' }));
    await act(async () => { releaseLogout(); });
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.historyAction).toBe('REPLACE');
    await back(router);
    // the area entry is guarded: signed out -> the login form again, by replace (no loop, no extra entry)
    expect(router.state.location.pathname).toBe('/login');
    expect((router.state.location.state as { from: { pathname: string } }).from.pathname).toBe(first);
    expect(loginForm()).toBeInTheDocument();
  });

  it('a double click on the footer "Выйти" still leaves one entry', async () => {
    signInStore(['ROLE_ADMIN']);
    const router = app(['/', '/admin/users'], 1);
    const out = screen.getByRole('button', { name: 'Выйти' });
    fireEvent.click(out);
    fireEvent.click(out);
    await act(async () => { releaseLogout(); });
    await act(async () => { releaseLogout(); });
    expect(router.state.location.pathname).toBe('/login');
    await back(router);
    expect(router.state.location.pathname).toBe('/');
  });

  it('the profile tab "Выйти" (Профиль): replace too', async () => {
    signInStore(['ROLE_USER']);
    const router = createMemoryRouter([
      { path: '/', element: <p>HOME</p> },
      { path: '/login', element: <LoginPage /> },
      { path: '/profile/*', element: <ProfilePage /> },
    ], { initialEntries: ['/', '/profile/settings'], initialIndex: 1 });
    render(<RouterProvider router={router} />);
    fireEvent.click(await screen.findByRole('button', { name: /Выйти/ }));
    await act(async () => { releaseLogout(); });
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.historyAction).toBe('REPLACE');
    await back(router);
    expect(router.state.location.pathname).toBe('/');
  });
});

describe('NAV-BACK-LOGIN: /login with a live session', () => {
  it.each([[['ROLE_USER'], '/'], [['ROLE_ADMIN'], '/admin'], [['ROLE_DIAGNOSTIC'], '/diagnostic']])(
    '%j: no form, the role home by replace', (roles, home) => {
      signInStore(roles);
      const router = app(['/x', '/login'], 1);
      expect(router.state.location.pathname).toBe(home);
      expect(router.state.historyAction).toBe('REPLACE');
      expect(loginForm()).not.toBeInTheDocument();
    });

  it('a safe return path is honoured', () => {
    signInStore(['ROLE_USER']);
    const router = app([{ pathname: '/login', state: { from: { pathname: '/profile/watched', search: '?x=1', hash: '' } } }]);
    expect(router.state.location).toMatchObject({ pathname: '/profile/watched', search: '?x=1' });
  });

  it.each([
    'https://evil.example/x', '//evil.example', '/\\evil.example', 'javascript:alert(1)', '/login', '/auth/telegram',
    { pathname: '//evil.example' }, { pathname: 42 }, 42, null,
  ])('return path %j -> the role home, never off the app', from => {
    signInStore(['ROLE_ADMIN']);
    const router = app([{ pathname: '/login', state: { from } }]);
    expect(router.state.location.pathname).toBe('/admin');
  });

  it.each([['an expired token', token(['ROLE_USER'], -60)], ['an unreadable token', 'x'], ['a token without exp', 'a.eyJzdWIiOiJ1In0.s']])(
    '%s is no session: the form stays (no redirect loop)', async (_name, t) => {
      signInStore(['ROLE_USER'], 0, t);
      const router = app(['/login']);
      await act(async () => {});
      expect(router.state.location.pathname).toBe('/login');
      expect(loginForm()).toBeInTheDocument();
    });

  it('an expired token on a deep link: the guard and the login page agree - one hop, the form', async () => {
    signInStore(['ROLE_USER'], -60);
    const router = app(['/profile/watched']);
    await act(async () => {});
    expect(router.state.location.pathname).toBe('/login');
    expect(loginForm()).toBeInTheDocument();
  });
});

describe('NAV-BACK-LOGIN: Back across documents (back/forward cache)', () => {
  // the browser QA found it: a deep link typed after signing out is a new document, so Back restores the
  // old /login document from the bfcache - with its signed-out store, while storage holds the new session
  const pageshow = (persisted: boolean) => {
    const e = new Event('pageshow');
    Object.defineProperty(e, 'persisted', { value: persisted });
    return e;
  };
  const storeElsewhere = (roles: string[]) => localStorage.setItem('auth-storage', JSON.stringify({
    state: { accessToken: token(roles), refreshToken: 'r', isAuthenticated: true, user: { id: 1, username: 'u', email: null, roles } },
    version: 0,
  }));

  it.each([[['ROLE_USER'], '/'], [['ROLE_ADMIN'], '/admin']])('%j: a restored /login re-reads the session and goes on to %s', async (roles, home) => {
    const router = app(['/login']);
    expect(loginForm()).toBeInTheDocument();
    storeElsewhere(roles);
    await act(async () => { window.dispatchEvent(pageshow(true)); });
    expect(router.state.location.pathname).toBe(home);
    expect(router.state.historyAction).toBe('REPLACE');
  });

  it('an ordinary page show (not from the cache) does not touch the session', async () => {
    const router = app(['/login']);
    storeElsewhere(['ROLE_USER']);
    await act(async () => { window.dispatchEvent(pageshow(false)); });
    expect(router.state.location.pathname).toBe('/login');
    expect(loginForm()).toBeInTheDocument();
  });

  it('a restored /login with a signed-out storage keeps the form', async () => {
    const router = app(['/login']);
    await act(async () => { window.dispatchEvent(pageshow(true)); });
    expect(router.state.location.pathname).toBe('/login');
    expect(loginForm()).toBeInTheDocument();
  });
});

describe('NAV-BACK-LOGIN: the whole round trip', () => {
  it('sign out -> deep link -> sign in -> Back does not show the form; Forward returns to the page', async () => {
    signInStore(['ROLE_USER']);
    const router = app(['/', '/profile/settings'], 1);
    await signOutFromHeader();                                          // [/, /login]
    await act(async () => { await router.navigate('/profile/watched'); }); // guard -> /login (replace)
    expect(router.state.location.pathname).toBe('/login');
    await submitLogin(['ROLE_USER']);
    expect(await screen.findByText('PROFILE AREA')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/profile/watched');
    await back(router);                                                  // the entry the sign-out left
    // a live session is sent on from it: to the page signed out of (the guard's return path, when the
    // guard replaced the entry first) or the role home - never the form, never /login
    expect(loginForm()).not.toBeInTheDocument();
    expect(['/profile/settings', '/']).toContain(router.state.location.pathname);
    await act(async () => { await router.navigate(1); });
    expect(router.state.location.pathname).toBe('/profile/watched');
  });

  it('a double submit signs in once and lands once', async () => {
    const router = app([{ pathname: '/login', state: { from: { pathname: '/profile/watched' } } }]);
    let answer: (v: unknown) => void = () => {};
    axiosPost.mockImplementation(() => new Promise(r => { answer = r; }));
    fireEvent.change(screen.getByPlaceholderText('Имя пользователя'), { target: { value: 'u' } });
    fireEvent.change(screen.getByPlaceholderText('Пароль'), { target: { value: 'secret12' } });
    fireEvent.click(screen.getByRole('button', { name: /^Войти$/ }));
    fireEvent.click(screen.getByRole('button', { name: /Входим/ }));
    await act(async () => { answer({ data: { accessToken: token(['ROLE_USER']), refreshToken: 'r' } }); });
    expect(axiosPost).toHaveBeenCalledTimes(1);
    expect(router.state.location.pathname).toBe('/profile/watched');
    await back(router);
    expect(loginForm()).not.toBeInTheDocument();
  });
});
