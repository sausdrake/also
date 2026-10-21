import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { StrictMode } from 'react';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { TelegramLoginButton } from './TelegramLoginButton';
import { TelegramLinkCard } from './TelegramLinkCard';
import { TelegramCallbackPage } from '../../pages/TelegramCallbackPage';
import { browser } from '../../api/telegramAuth';
import { homePathFor, sessionUserFromToken } from '../../auth/session';
import { useAuthStore } from '../../store/authStore';
import api from '../../api/client';

/**
 * AUTH-TELEGRAM-01: the browser side - start (binding kept in this tab, one round trip per click), the callback
 * (code + state + binding to the backend exactly once, session from the backend's own token), denial / expiry /
 * retry, and linking for the signed-in account.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn(), create: vi.fn(() => ({})) } }));
vi.mock('../../api/client', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const PENDING = 'also.telegram.pending';
const AUTH_URL = 'https://oauth.telegram.org/auth?client_id=1&state=abc';

const b64url = (o: object) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o))))
  .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
const jwt = (payload: object) => `${b64url({ alg: 'HS256' })}.${b64url(payload)}.sig`;
const httpError = (status: number, code: string, message: string) =>
  Object.assign(new Error(String(status)), { isAxiosError: true, response: { status, data: { status, code, message } } });

let redirect: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  sessionStorage.clear();
  axiosGet.mockReset().mockResolvedValue({ data: { enabled: true } });
  axiosPost.mockReset().mockResolvedValue({ data: { authorizationUrl: AUTH_URL } });
  apiGet.mockReset();
  apiPost.mockReset();
  redirect = vi.spyOn(browser, 'redirect').mockImplementation(() => undefined);
  useAuthStore.setState({ user: null, accessToken: null, refreshToken: null, isAuthenticated: false });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('TelegramLoginButton', () => {
  it('is not shown while Telegram is not configured on the server', async () => {
    axiosGet.mockResolvedValue({ data: { enabled: false } });
    const { container } = render(<TelegramLoginButton />);
    await waitFor(() => expect(axiosGet).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  it('starts one round trip: random binding kept in this tab, sent to the backend, then Telegram', async () => {
    render(<TelegramLoginButton />);
    const button = await screen.findByRole('button', { name: /Войти через Telegram/ });
    act(() => { button.click(); button.click(); });   // a double tap before React re-renders
    await waitFor(() => expect(redirect).toHaveBeenCalledWith(AUTH_URL));
    expect(axiosPost).toHaveBeenCalledTimes(1);
    const [url, body] = axiosPost.mock.calls[0];
    expect(url).toMatch(/\/api\/auth\/telegram\/authorize$/);
    expect(body.binding).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(body.includePhone).toBe(false);
    expect(JSON.parse(sessionStorage.getItem(PENDING)!)).toEqual({ binding: body.binding, purpose: 'LOGIN' });
    expect(JSON.stringify(body)).not.toMatch(/secret|telegramId|userId/i);
  });

  it('a refused start shows the server message and can be retried', async () => {
    axiosPost.mockRejectedValueOnce(httpError(503, 'TELEGRAM_NOT_CONFIGURED', 'Вход через Telegram сейчас недоступен.'));
    render(<TelegramLoginButton label="Продолжить с Telegram" />);
    fireEvent.click(await screen.findByRole('button', { name: /Продолжить с Telegram/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Вход через Telegram сейчас недоступен.');
    expect(redirect).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: /Продолжить с Telegram/ }));
    await waitFor(() => expect(redirect).toHaveBeenCalledWith(AUTH_URL));
  });
});

function renderCallback(search: string, strict = false) {
  const router = createMemoryRouter([
    { path: '/auth/telegram/callback', element: <TelegramCallbackPage /> },
    { path: '/', element: <p>HOME</p> },
    { path: '/admin', element: <p>ADMIN</p> },
    { path: '/profile/settings', element: <p>PROFILE</p> },
    { path: '/login', element: <p>LOGIN</p> },
  ], { initialEntries: [`/auth/telegram/callback${search}`] });
  render(strict ? <StrictMode><RouterProvider router={router} /></StrictMode> : <RouterProvider router={router} />);
  return router;
}

describe('TelegramCallbackPage', () => {
  it('success: code + state + this tab\'s binding go to the backend once; the session comes from the backend token', async () => {
    sessionStorage.setItem(PENDING, JSON.stringify({ binding: 'b'.repeat(43), purpose: 'LOGIN' }));
    const access = jwt({ userId: 42, username: 'user_ab12', email: null, roles: ['ROLE_USER'] });
    axiosPost.mockResolvedValue({ data: { accessToken: access, refreshToken: 'r1' } });
    renderCallback('?code=c1&state=s1', true);

    expect(await screen.findByText('HOME')).toBeInTheDocument();
    expect(axiosPost).toHaveBeenCalledTimes(1);                                // StrictMode double effect -> still once
    expect(axiosPost.mock.calls[0][0]).toMatch(/\/api\/auth\/telegram\/callback$/);
    expect(axiosPost.mock.calls[0][1]).toEqual({ code: 'c1', state: 's1', binding: 'b'.repeat(43) });
    expect(useAuthStore.getState()).toMatchObject({ isAuthenticated: true, accessToken: access, refreshToken: 'r1',
      user: { id: 42, username: 'user_ab12', email: null, roles: ['ROLE_USER'] } });
    expect(sessionStorage.getItem(PENDING)).toBeNull();                       // the round trip cannot be reused
  });

  it('declined in Telegram: says so, sends nothing, offers a retry', async () => {
    sessionStorage.setItem(PENDING, JSON.stringify({ binding: 'b'.repeat(43), purpose: 'LOGIN' }));
    renderCallback('?error=access_denied&state=s1');
    expect(await screen.findByRole('alert')).toHaveTextContent('Вы отменили вход через Telegram.');
    expect(axiosPost).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole('button', { name: 'Попробовать снова' }));
    await waitFor(() => expect(redirect).toHaveBeenCalledWith(AUTH_URL));
  });

  it.each([
    ['no round trip started in this tab', '?code=c&state=s', null],
    ['no code', '?state=s', JSON.stringify({ binding: 'b'.repeat(43), purpose: 'LOGIN' })],
    ['garbage pending value', '?code=c&state=s', '{not json'],
  ])('%s: invalid link message, nothing sent', async (_label, search, pending) => {
    if (pending) sessionStorage.setItem(PENDING, pending);
    renderCallback(search);
    expect(await screen.findByRole('alert')).toHaveTextContent(/недействительна или устарела/);
    expect(axiosPost).not.toHaveBeenCalled();
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
  });

  it('a backend refusal shows its own message and does not sign in', async () => {
    sessionStorage.setItem(PENDING, JSON.stringify({ binding: 'b'.repeat(43), purpose: 'LOGIN' }));
    axiosPost.mockRejectedValue(httpError(400, 'TELEGRAM_INVALID_STATE', 'Ссылка входа через Telegram недействительна или устарела. Начните вход заново.'));
    renderCallback('?code=c&state=s');
    expect(await screen.findByRole('alert')).toHaveTextContent('Начните вход заново');
    expect(useAuthStore.getState().isAuthenticated).toBe(false);
    expect(screen.getByRole('link', { name: 'Войти по логину и паролю' })).toBeInTheDocument();
  });

  it('a link round trip finishes through the authenticated API and returns to the profile', async () => {
    sessionStorage.setItem(PENDING, JSON.stringify({ binding: 'b'.repeat(43), purpose: 'LINK' }));
    apiPost.mockResolvedValue({ data: { linked: true } });
    renderCallback('?code=c2&state=s2');
    expect(await screen.findByText('PROFILE')).toBeInTheDocument();
    expect(apiPost).toHaveBeenCalledWith('/account/telegram/link/complete', { code: 'c2', state: 's2', binding: 'b'.repeat(43) });
    expect(axiosPost).not.toHaveBeenCalled();
  });
});

describe('session from the backend token', () => {
  it('reads id, username, roles; email may be null; lands by role', () => {
    const user = sessionUserFromToken(jwt({ userId: 7, username: 'адм', email: null, roles: ['ROLE_ADMIN'] }));
    expect(user).toEqual({ id: 7, username: 'адм', email: null, roles: ['ROLE_ADMIN'] });
    expect(homePathFor(user.roles)).toBe('/admin');
    expect(homePathFor(['ROLE_DIAGNOSTIC'])).toBe('/diagnostic');
    expect(homePathFor(['ROLE_USER'])).toBe('/');
    expect(sessionUserFromToken('garbage', 'fallback')).toEqual({ id: 0, username: 'fallback', email: null, roles: [] });
  });
});

describe('TelegramLinkCard', () => {
  it('linked: shows the Telegram display name and phone state - never an id or number', async () => {
    apiGet.mockResolvedValue({ data: { enabled: true, linked: true, displayName: 'Айбек', phoneVerified: true, botCanMessage: false } });
    render(<TelegramLinkCard />);
    expect(await screen.findByTestId('telegram-link')).toHaveTextContent('Привязан (Айбек)');
    expect(screen.getByTestId('telegram-link')).toHaveTextContent('телефон подтверждён');
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('not linked: one click starts linking for the current account', async () => {
    apiGet.mockResolvedValue({ data: { enabled: true, linked: false, displayName: null, phoneVerified: false, botCanMessage: false } });
    apiPost.mockResolvedValue({ data: { authorizationUrl: AUTH_URL } });
    render(<TelegramLinkCard />);
    const button = await screen.findByRole('button', { name: 'Привязать Telegram' });
    act(() => { button.click(); button.click(); });
    await waitFor(() => expect(redirect).toHaveBeenCalledWith(AUTH_URL));
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost.mock.calls[0][0]).toBe('/account/telegram/link/authorize');
    expect(Object.keys(apiPost.mock.calls[0][1])).toEqual(['binding']);
    expect(JSON.parse(sessionStorage.getItem(PENDING)!).purpose).toBe('LINK');
  });

  it('a refusal (e.g. admin account) is shown', async () => {
    apiGet.mockResolvedValue({ data: { enabled: true, linked: false, displayName: null, phoneVerified: false, botCanMessage: false } });
    apiPost.mockRejectedValue(httpError(403, 'TELEGRAM_LOGIN_NOT_ALLOWED', 'Для этого аккаунта вход через Telegram недоступен. Войдите по логину и паролю.'));
    render(<TelegramLinkCard />);
    fireEvent.click(await screen.findByRole('button', { name: 'Привязать Telegram' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Для этого аккаунта вход через Telegram недоступен');
  });

  it('Telegram disabled and nothing linked: the card is hidden', async () => {
    apiGet.mockResolvedValue({ data: { enabled: false, linked: false, displayName: null, phoneVerified: false, botCanMessage: false } });
    const { container } = render(<TelegramLinkCard />);
    await waitFor(() => expect(apiGet).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });
});
