import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';

/**
 * AUTH-TG-UX-01: a Telegram-only account has no password, so the profile does not offer "Сменить пароль"
 * (it could only fail as "wrong current password"); a password account still gets it.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;

function mockApi(password: { passwordSet: boolean } | 'error') {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/account/password') {
      return password === 'error' ? Promise.reject(new Error('HTTP 500')) : Promise.resolve({ data: password });
    }
    if (url === '/deposits/my') return Promise.resolve({ data: { balance: 0, blockedAmount: 0, availableBalance: 0 } });
    return Promise.resolve({ data: [] });
  });
}

beforeEach(() => {
  useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 7, username: 'user_abcdefghij', email: null as unknown as string, roles: ['ROLE_USER'] } });
});

function renderSettings() {
  const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile/settings'] });
  render(<RouterProvider router={router} />);
}

describe('AUTH-TG-UX-01: change password only for accounts that have one', () => {
  it('Telegram-only account: no "Сменить пароль", a plain note instead', async () => {
    mockApi({ passwordSet: false });
    renderSettings();

    expect(await screen.findByText('Пароль не задан — вход выполняется через Telegram.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Сменить пароль' })).toBeNull();
    expect(screen.getByRole('button', { name: 'Выйти' })).toBeInTheDocument();
  });

  it('password account: "Сменить пароль" stays', async () => {
    mockApi({ passwordSet: true });
    renderSettings();

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/account/password'));
    expect(await screen.findByRole('button', { name: 'Сменить пароль' })).toBeInTheDocument();
    expect(screen.queryByText(/Пароль не задан/)).toBeNull();
  });

  it('status unknown (request failed): the button stays - the backend refuses a passwordless change itself', async () => {
    mockApi('error');
    renderSettings();

    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/account/password'));
    expect(await screen.findByRole('button', { name: 'Сменить пароль' })).toBeInTheDocument();
  });
});
