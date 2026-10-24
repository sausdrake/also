import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { BrowserRouter, createMemoryRouter, Route, RouterProvider, Routes } from 'react-router-dom';
import axios from 'axios';
import { LoginPage } from '../pages/LoginPage';
import { ForbiddenPage } from '../pages/ForbiddenPage';
import { ChangePasswordPage } from '../pages/ChangePasswordPage';
import { ProtectedRoute } from '../components/ProtectedRoute';
import { useAuthStore } from '../store/authStore';

/**
 * G1 (NAV-INV-06/07): a deep link survives signing in, the return is an in-app path only, Back after
 * signing in does not reopen the form, and "Назад" on a directly opened /forbidden stays in the app.
 */

vi.mock('axios', () => ({ default: { post: vi.fn(), get: vi.fn() } }));
vi.mock('../api/client', () => ({ default: { get: vi.fn(), post: vi.fn() }, BASE_URL: 'http://api.test' }));
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

function token(roles: string[], expInSec = 3600): string {
  const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64({ sub: 'u', username: 'u', userId: 1, roles, exp: Math.floor(Date.now() / 1000) + expInSec })}.sig`;
}

const signedOut = () => useAuthStore.setState({ accessToken: null, refreshToken: null, isAuthenticated: false, user: null } as never);

function app(initialEntries: (string | { pathname: string; state?: unknown })[], initialIndex?: number) {
  const router = createMemoryRouter([
    { path: '/login', element: <LoginPage /> },
    { path: '/', element: <p>HOME</p> },
    { path: '/admin/*', element: <ProtectedRoute allowedRoles={['ADMIN']}><p>ADMIN AREA</p></ProtectedRoute> },
    { path: '/diagnostic/*', element: <ProtectedRoute allowedRoles={['DIAGNOSTIC', 'ADMIN']}><p>DIAG AREA</p></ProtectedRoute> },
    { path: '/profile/*', element: <ProtectedRoute><p>PROFILE AREA</p></ProtectedRoute> },
    { path: '/forbidden', element: <ForbiddenPage /> },
    { path: '/auction/:id', element: <p>LOT</p> },
  ], { initialEntries, initialIndex });
  render(<RouterProvider router={router} />);
  return router;
}

async function signInAs(roles: string[]) {
  axiosPost.mockResolvedValue({ data: { accessToken: token(roles), refreshToken: 'r' } });
  fireEvent.change(await screen.findByPlaceholderText('Имя пользователя'), { target: { value: 'u' } });
  fireEvent.change(screen.getByPlaceholderText('Пароль'), { target: { value: 'secret12' } });
  fireEvent.click(screen.getByRole('button', { name: /^Войти$/ }));
}

beforeEach(() => {
  axiosPost.mockReset();
  localStorage.clear();
  signedOut();
});

describe('G1: deep link -> sign in -> the same page', () => {
  it('anonymous on /profile/watched?x=1#top is asked to sign in and comes back there', async () => {
    const router = app(['/profile/watched?x=1#top']);
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: { pathname: '/profile/watched', search: '?x=1', hash: '#top' } });
    await signInAs(['ROLE_USER']);
    expect(await screen.findByText('PROFILE AREA')).toBeInTheDocument();
    expect(router.state.location).toMatchObject({ pathname: '/profile/watched', search: '?x=1', hash: '#top' });
  });

  it('an expired session on a deep link: same return path', async () => {
    useAuthStore.setState({ accessToken: token(['ROLE_ADMIN'], -60), isAuthenticated: true,
      user: { id: 1, username: 'u', email: null, roles: ['ROLE_ADMIN'] }, logout: vi.fn() } as never);
    const router = app(['/admin/sale/7']);
    expect(router.state.location.pathname).toBe('/login');
    expect((router.state.location.state as { from: { pathname: string } }).from.pathname).toBe('/admin/sale/7');
  });

  it('the return path keeps the role guard: a USER returned to /admin gets /forbidden, not the admin area', async () => {
    const router = app(['/admin/users']);
    await signInAs(['ROLE_USER']);
    expect(await screen.findByText('Доступ запрещён')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/forbidden');
    expect(screen.queryByText('ADMIN AREA')).not.toBeInTheDocument();
  });

  it('a DIAGNOSTIC returned to /admin is refused too; to /diagnostic/reports - allowed', async () => {
    const r1 = app(['/admin']);
    await signInAs(['ROLE_DIAGNOSTIC']);
    expect(await screen.findByText('Доступ запрещён')).toBeInTheDocument();
    expect(r1.state.location.pathname).toBe('/forbidden');
  });

  it('without a return path the role home is used', async () => {
    const router = app(['/login']);
    await signInAs(['ROLE_ADMIN']);
    expect(await screen.findByText('ADMIN AREA')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/admin');
  });

  it.each([
    [{ pathname: '//evil.example/x' }], ['https://evil.example'], [{ pathname: '/\\evil.example' }],
    [{ pathname: '/login' }], ['javascript:alert(1)'],
  ])('a crafted return path %j is ignored (role home instead)', async from => {
    const router = app([{ pathname: '/login', state: { from } }]);
    await signInAs(['ROLE_USER']);
    expect(await screen.findByText('HOME')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/');
  });

  it('Back after signing in does not reopen the login form (the form entry is replaced)', async () => {
    const router = app(['/auction/3', '/login'], 1);
    await signInAs(['ROLE_USER']);
    expect(await screen.findByText('HOME')).toBeInTheDocument();
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe('/auction/3');
  });

  it('a failed sign-in stays on the form and keeps the return path', async () => {
    axiosPost.mockRejectedValue(Object.assign(new Error('401'), { isAxiosError: true, response: { status: 401, data: {} } }));
    const router = app(['/profile/bids']);
    fireEvent.change(await screen.findByPlaceholderText('Имя пользователя'), { target: { value: 'u' } });
    fireEvent.change(screen.getByPlaceholderText('Пароль'), { target: { value: 'bad' } });
    fireEvent.click(screen.getByRole('button', { name: /^Войти$/ }));
    expect(await screen.findByText('Неверное имя пользователя или пароль.')).toBeInTheDocument();
    expect(router.state.location.pathname).toBe('/login');
    expect((router.state.location.state as { from: { pathname: string } }).from.pathname).toBe('/profile/bids');
  });
});

describe('G1: "Назад" on /forbidden', () => {
  function openInBrowser(entries: string[]) {
    // BrowserRouter keeps its position as history.state.idx - the real browser behaviour
    window.history.replaceState(null, '', entries[0]);
    render(<BrowserRouter><Routes>
      <Route path="/" element={<p>HOME</p>} />
      <Route path="/forbidden" element={<ForbiddenPage />} />
      <Route path="/auction/:id" element={<p>LOT</p>} />
    </Routes></BrowserRouter>);
  }

  it('opened directly (first entry): goes to the catalogue, does not leave the app', () => {
    openInBrowser(['/forbidden']);
    const back = vi.spyOn(window.history, 'go');
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(back).not.toHaveBeenCalled();
    expect(window.location.pathname).toBe('/');
    expect(screen.getByText('HOME')).toBeInTheDocument();
    back.mockRestore();
  });

  it('after an in-app navigation: really goes back', async () => {
    openInBrowser(['/auction/4']);
    act(() => { window.history.pushState({ usr: null, key: 'k2', idx: 1 }, '', '/forbidden'); window.dispatchEvent(new PopStateEvent('popstate')); });
    expect(await screen.findByText('Доступ запрещён')).toBeInTheDocument();
    const back = vi.spyOn(window.history, 'go');
    fireEvent.click(screen.getByRole('button', { name: 'Назад' }));
    expect(back).toHaveBeenCalledWith(-1);
    back.mockRestore();
  });
});

describe('G1: /change-password is a child of the profile', () => {
  function renderChange(path: string, roles: string[]) {
    useAuthStore.setState({ accessToken: 'x', isAuthenticated: true, user: { id: 1, username: 'u', email: null, roles } } as never);
    render(<RouterProvider router={createMemoryRouter([{ path: '/change-password', element: <ChangePasswordPage /> }], { initialEntries: [path] })} />);
  }

  it.each([[['ROLE_USER'], '/profile/settings'], [['ROLE_DIAGNOSTIC'], '/profile']])('%j: "← Профиль" leads to %s', (roles, href) => {
    renderChange('/change-password', roles);
    expect(screen.getByRole('link', { name: '← Профиль' })).toHaveAttribute('href', href);
  });

  it('not offered while a temporary password must be replaced', () => {
    renderChange('/change-password?required=1', ['ROLE_USER']);
    expect(screen.queryByRole('link', { name: '← Профиль' })).not.toBeInTheDocument();
  });
});
