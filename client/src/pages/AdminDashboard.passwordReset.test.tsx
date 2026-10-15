import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * AUTH-01: an administrator resets a user's password only after confirming the identity check,
 * and sees the temporary password once.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), patch: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const user = { id: 42, username: 'buyer42', email: 'b@example.com', active: true, trusted: true, roles: ['ROLE_USER'] };

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) =>
    Promise.resolve({ data: url.startsWith('/admin/users') ? [user] : [] }));
  apiPost.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openReset() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/users'] }
  );
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByRole('button', { name: 'Просмотр' }));
  fireEvent.click(screen.getByRole('button', { name: 'Сбросить пароль' }));
}

describe('AdminDashboard - password reset', () => {
  it('asks to confirm the KYC identity check; cancel sends nothing', async () => {
    await openReset();
    expect(screen.getByText(/сверьте личность/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Отмена' }));
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('after confirmation shows the temporary password once', async () => {
    apiPost.mockResolvedValue({ data: { temporaryPassword: 'Abc23defGhk45mnP' } });
    await openReset();
    fireEvent.click(screen.getByRole('button', { name: 'Личность сверена, сбросить' }));

    expect(await screen.findByTestId('temporary-password')).toHaveTextContent('Abc23defGhk45mnP');
    expect(apiPost).toHaveBeenCalledWith('/admin/users/42/password-reset');

    fireEvent.click(screen.getByRole('button', { name: 'Готово' }));
    await waitFor(() => expect(screen.queryByTestId('temporary-password')).not.toBeInTheDocument());
  });
});
