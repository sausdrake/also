import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/**
 * S11 audit: self-service top-up is disabled on the server (POST /deposits/deposit always answers
 * 409) and no payment provider is connected, so the deposit tab must not offer a top-up form that
 * can only fail - it explains how funding works instead. Withdrawal stays available.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/deposits/my') return Promise.resolve({ data: { balance: 1000, blockedAmount: 0, availableBalance: 1000 } });
    if (url === '/withdrawals/my') return Promise.resolve({ data: [
      { id: 7, amount: 300, status: 'REJECTED', createdAt: '2026-10-14T10:00:00Z', processedAt: '2026-10-14T12:00:00Z',
        rejectionReason: 'Неверные реквизиты' },
      { id: 8, amount: 200, status: 'PROCESSING', createdAt: '2026-10-15T10:00:00Z', processedAt: null, rejectionReason: null },
    ] });
    if (url === '/deposits/my/transactions') return Promise.resolve({ data: [
      { id: 1, type: 'ADMIN_ADJUSTMENT', amount: 1000, description: 'RECONCILIATION', createdAt: '2026-10-15T10:00:00Z' },
    ] });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('ProfilePage deposit tab - top-up', () => {
  it('explains how funding works and offers no top-up form', async () => {
    const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile'] });
    render(<RouterProvider router={router} />);

    const info = await screen.findByTestId('top-up-info');
    expect(info).toHaveTextContent('Зачисление средств выполняется администратором после проверки платежа');
    expect(screen.queryByRole('button', { name: 'Пополнить' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Вывести' })).toBeInTheDocument();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('shows transaction types by their Russian label', async () => {
    const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile'] });
    render(<RouterProvider router={router} />);

    expect(await screen.findByText('Корректировка администратором')).toBeInTheDocument();
    expect(screen.queryByText('ADMIN_ADJUSTMENT')).not.toBeInTheDocument();
  });

  it('F6: withdrawal creates a manual payout request with a stable idempotency key', async () => {
    const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile'] });
    render(<RouterProvider router={router} />);
    await screen.findByTestId('top-up-info');

    fireEvent.change(screen.getByPlaceholderText('Сумма'), { target: { value: '250' } });
    apiPost.mockRejectedValueOnce({ response: { data: { message: 'сеть' } } });
    fireEvent.click(screen.getByRole('button', { name: 'Вывести' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'Вывести' }));   // retry after the failure
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2));

    const [url, body] = apiPost.mock.calls[0];
    expect(url).toBe('/withdrawals');
    expect(body.amount).toBe(250);
    expect(apiPost.mock.calls[1][1].idempotencyKey).toBe(body.idempotencyKey);
    expect(await screen.findByText(/Заявка на вывод создана/)).toBeInTheDocument();
  });

  it('F6: lists own withdrawal requests with status and rejection reason', async () => {
    const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile'] });
    render(<RouterProvider router={router} />);

    const list = await screen.findByTestId('my-withdrawals');
    expect(within(list).getByText('Отклонено, сумма возвращена')).toBeInTheDocument();
    expect(within(list).getByText('Причина: Неверные реквизиты')).toBeInTheDocument();
    expect(within(list).getByText('В обработке')).toBeInTheDocument();
  });
});
