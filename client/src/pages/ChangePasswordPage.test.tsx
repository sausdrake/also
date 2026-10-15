import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ChangePasswordPage } from './ChangePasswordPage';
import api from '../api/client';

/** AUTH-01: the change-password page. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const logout = vi.fn();
vi.mock('../store/authStore', () => ({
  useAuthStore: () => ({ logout }),
}));

const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiPost.mockReset();
  logout.mockReset().mockResolvedValue(undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
});

function renderPage(entry = '/change-password') {
  const router = createMemoryRouter(
    [
      { path: '/change-password', element: <ChangePasswordPage /> },
      { path: '/login', element: <p>login page</p> },
    ],
    { initialEntries: [entry] }
  );
  render(<RouterProvider router={router} />);
  return router;
}

function fill(current: string, next: string, confirm: string) {
  const [c, n, r] = Array.from(document.querySelectorAll('input[type=password]')) as HTMLInputElement[];
  fireEvent.change(c, { target: { value: current } });
  fireEvent.change(n, { target: { value: next } });
  fireEvent.change(r, { target: { value: confirm } });
  fireEvent.click(screen.getByRole('button', { name: 'Сменить пароль' }));
}

describe('ChangePasswordPage', () => {
  it('explains the temporary password when the change is required', () => {
    renderPage('/change-password?required=1');
    expect(screen.getByRole('status')).toHaveTextContent('временным паролем');
    expect(screen.getByText('Временный пароль')).toBeInTheDocument();
  });

  it('mismatched confirmation: error shown, nothing sent', () => {
    renderPage();
    fill('old-pass', 'new-pass-1', 'new-pass-2');
    expect(screen.getByRole('alert')).toHaveTextContent('не совпадают');
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('success: sends both passwords, signs out and goes to the login page', async () => {
    apiPost.mockResolvedValue({ data: undefined });
    const router = renderPage();
    fill('old-pass', 'new-pass-1', 'new-pass-1');

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(apiPost).toHaveBeenCalledWith('/account/password', { currentPassword: 'old-pass', newPassword: 'new-pass-1' });
    expect(logout).toHaveBeenCalledTimes(1);
    expect(router.state.location.state).toEqual({ notice: 'Пароль изменён. Войдите с новым паролем.' });
  });

  it('server refusal: its message is shown, the user stays on the page', async () => {
    apiPost.mockRejectedValue({ response: { data: { message: 'Текущий пароль указан неверно' } } });
    const router = renderPage();
    fill('wrong', 'new-pass-1', 'new-pass-1');

    expect(await screen.findByRole('alert')).toHaveTextContent('Текущий пароль указан неверно');
    expect(router.state.location.pathname).toBe('/change-password');
    expect(logout).not.toHaveBeenCalled();
  });
});
