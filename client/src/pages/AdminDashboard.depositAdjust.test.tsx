import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * S11 audit: the admin deposit adjustment is the only working way to fund a deposit, and its
 * endpoint requires a structured reason and a reference. The form used to send a free-text
 * comment instead, so every adjustment was refused with 400.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const deposit = { id: 1, userId: 5, balance: 1000, blockedAmount: 0, availableBalance: 1000, status: 'ACTIVE' };
const user = { id: 5, username: 'seller5', email: 's5@test.com' };

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/admin/deposits') return Promise.resolve({ data: [deposit] });
    if (url === '/admin/users') return Promise.resolve({ data: [user] });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openAdjust() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/deposits'] }
  );
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('Корректировать'));
  fireEvent.change(screen.getByPlaceholderText('Например: 5000 или -1000'), { target: { value: '5000' } });
}

describe('AdminDashboard DepositsPage - deposit adjustment', () => {
  it('cannot be applied without a reason and a reference', async () => {
    await openAdjust();
    expect(screen.getByText('Применить')).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Основание (ссылка) *'), { target: { value: 'TICKET-1' } });
    expect(screen.getByText('Применить')).toBeDisabled();   // reference alone, no reason

    fireEvent.change(screen.getByLabelText('Причина *'), { target: { value: 'RECONCILIATION' } });

    fireEvent.change(screen.getByLabelText('Основание (ссылка) *'), { target: { value: '   ' } });
    expect(screen.getByText('Применить')).toBeDisabled();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('sends the body the adjust endpoint accepts: amount, reason, reference, idempotencyKey', async () => {
    await openAdjust();
    fireEvent.change(screen.getByLabelText('Причина *'), { target: { value: 'RECONCILIATION' } });
    fireEvent.change(screen.getByLabelText('Основание (ссылка) *'), { target: { value: ' PAYMENT:TX-1 ' } });
    fireEvent.click(screen.getByText('Применить'));

    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1));
    const [url, body] = apiPost.mock.calls[0];
    expect(url).toBe('/admin/deposits/5/adjust');
    expect(body).toEqual({ amount: 5000, reason: 'RECONCILIATION', reference: 'PAYMENT:TX-1', idempotencyKey: expect.any(String) });
    expect(body.idempotencyKey).not.toBe('');
    expect(body).not.toHaveProperty('comment');
  });

  it('offers exactly the reasons the server accepts', async () => {
    await openAdjust();
    const values = Array.from((screen.getByLabelText('Причина *') as HTMLSelectElement).options)
      .map(o => o.value).filter(Boolean);
    expect(values).toEqual(['RECONCILIATION', 'MANUAL_REFUND', 'CHARGEBACK', 'SUPPORT_CORRECTION', 'OTHER']);
  });
});

describe('FE-INFLIGHT-01 (BL-21): "Применить" sends one adjustment at a time', () => {
  it('a double click posts once and the button is disabled while it is in flight', async () => {
    let resolve!: (v: unknown) => void;
    apiPost.mockReset().mockImplementation(() => new Promise(res => { resolve = res; }));
    await openAdjust();
    fireEvent.change(screen.getByLabelText('Причина *'), { target: { value: 'RECONCILIATION' } });
    fireEvent.change(screen.getByLabelText('Основание (ссылка) *'), { target: { value: 'BANK-1' } });
    const button = screen.getByRole('button', { name: 'Применить' });
    // two clicks handled before React re-renders the button (both reach the handler)
    act(() => { button.click(); button.click(); });
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Отправка…' })).toBeDisabled();
    resolve({ data: {} });
    expect(await screen.findByText('Депозит скорректирован на +5000 сом')).toBeInTheDocument();
  });

  it('after a refusal the same adjustment can be retried (same idempotency key)', async () => {
    apiPost.mockReset().mockRejectedValueOnce({ response: { data: { message: 'Сервис временно недоступен' } } }).mockResolvedValue({ data: {} });
    await openAdjust();
    fireEvent.change(screen.getByLabelText('Причина *'), { target: { value: 'RECONCILIATION' } });
    fireEvent.change(screen.getByLabelText('Основание (ссылка) *'), { target: { value: 'BANK-2' } });
    fireEvent.click(screen.getByRole('button', { name: 'Применить' }));
    expect(await screen.findByText('Сервис временно недоступен')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Применить' }));
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2));
    expect(apiPost.mock.calls[1][1].idempotencyKey).toBe(apiPost.mock.calls[0][1].idempotencyKey);
  });
});
