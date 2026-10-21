import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/** BL-24 / BL-18: a list that failed to load shows an error with a retry, never the empty state. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const down = () => Promise.reject(Object.assign(new Error('Network Error'), { isAxiosError: true }));

/** `failing` URLs fail until `heal()` is called; everything else answers with an empty body. */
function mockApi(failing: string, ok: unknown) {
  let healed = false;
  apiGet.mockImplementation((url: string) => {
    if (url.startsWith(failing)) return healed ? Promise.resolve({ data: ok }) : down();
    if (url.startsWith('/withdrawals/my') || url.startsWith('/deposits/my/transactions')) return Promise.resolve({ data: [] });
    return Promise.resolve({ data: {} });
  });
  return () => { healed = true; };
}

function renderAt(path: string) {
  const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: [path] });
  render(<RouterProvider router={router} />);
}

beforeEach(() => { apiGet.mockReset(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('BL-24: ERROR != EMPTY on the profile lists', () => {
  it.each([
    ['/profile/bids', '/auctions/participated', 'Вы ещё не участвовали ни в одном аукционе',
      [{ id: 7, vehicleInfo: 'Lot 7', currentPrice: 1, status: 'ACTIVE' }]],
    ['/profile/won', '/auctions/won', 'Вы ещё не выиграли ни одного аукциона',
      { content: [{ id: 7, vehicleInfo: 'Lot 7', currentPrice: 1, status: 'SOLD' }], totalPages: 1 }],
    ['/profile/requests', '/diagnostic/requests/my', 'Нет заявок на диагностику',
      { content: [{ id: 7, brand: 'Lot', model: '7', year: 2020, vin: 'WBA00000000000007', status: 'PENDING', createdAt: '2026-10-14T10:00:00Z' }] }],
  ])('%s: a failed load is an error with a retry, and the retry shows the data', async (path, url, empty, ok) => {
    const heal = mockApi(url, ok);
    renderAt(path);
    const alert = await screen.findByRole('alert');
    // FE-ERR-01 wording for the failure (here: no connection)
    expect(alert).toHaveTextContent('Нет связи с сервером');
    expect(screen.queryByText(empty)).not.toBeInTheDocument();
    heal();
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText(/Lot 7|Lot 7 2020/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('BL-18: deposit history that failed to load is not "Нет транзакций"', async () => {
    const heal = mockApi('/deposits/my/transactions', [{ id: 1, type: 'ADMIN_ADJUSTMENT', amount: 500, createdAt: '2026-10-14T10:00:00Z' }]);
    renderAt('/profile');
    expect(await screen.findByRole('alert')).toHaveTextContent('Нет связи с сервером');
    expect(screen.queryByText('Нет транзакций')).not.toBeInTheDocument();
    heal();
    fireEvent.click(screen.getAllByRole('button', { name: 'Повторить' })[0]);
    expect((await screen.findAllByText(/500/)).length).toBeGreaterThan(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
