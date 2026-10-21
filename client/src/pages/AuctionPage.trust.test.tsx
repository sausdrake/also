import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/** KYC-01: a verified but not trusted user is told about the trusted status, never asked to verify again. */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });
const TRUST = httpError(403, { status: 403, error: 'Forbidden', code: 'TRUSTED_PARTICIPANT_REQUIRED',
  message: 'Для участия в торгах нужен статус доверенного пользователя. Его выдаёт администратор: подтверждение личности не даёт этот статус автоматически.' });
const KYC = httpError(403, { status: 403, error: 'Forbidden', code: 'IDENTITY_VERIFICATION_REQUIRED', message: 'Пройдите верификацию личности.' });

const lot = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status, startingPrice: 1000000, currentPrice: 1000000, minBidStep: 10000,
  buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000, ...extra,
});

async function renderLot(status: string, extra: Record<string, unknown> = {}) {
  axiosGet.mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: lot(status, extra) }) : Promise.resolve({ data: {} }));
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

function bid() {
  fireEvent.change(screen.getByPlaceholderText('1 010 000'), { target: { value: '1010000' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ставка' }));
}

beforeEach(() => {
  axiosGet.mockReset();
  axiosPost.mockReset();
  useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 5, username: 'buyer', email: 'b@test.com', roles: ['ROLE_USER'] } });
});
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('KYC-01: trust refusal on the lot page', () => {
  it('bid refused by the trust gate -> identity confirmed + trusted status needed, no identity link, no "подтвердите личность"', async () => {
    axiosPost.mockRejectedValue(TRUST);
    await renderLot('ACTIVE');
    bid();

    const notice = (await screen.findByText('Подтверждение личности пройдено.')).closest('[role=alert]');
    expect(notice).toHaveTextContent('Чтобы делать ставки, нужен статус доверенного пользователя');
    expect(screen.queryByText(/подтвердите личность/)).toBeNull();
    expect(screen.queryByRole('link', { name: 'Пройти подтверждение личности' })).toBeNull();
  });

  it('Buy Now refused by the trust gate -> the trusted notice for Buy Now', async () => {
    axiosPost.mockRejectedValue(TRUST);
    await renderLot('CREATED', { buyNowPrice: 2000000 });
    fireEvent.click(screen.getByRole('button', { name: /Купить сейчас/ }));

    expect((await screen.findByText('Подтверждение личности пройдено.')).closest('[role=alert]'))
      .toHaveTextContent('Чтобы купить по цене Buy Now, нужен статус доверенного пользователя');
  });

  it('switching gates replaces the notice (KYC refusal, then trust refusal) - never both at once', async () => {
    axiosPost.mockRejectedValueOnce(KYC).mockRejectedValueOnce(TRUST);
    await renderLot('ACTIVE');
    bid();
    expect(await screen.findByText('Чтобы делать ставки, подтвердите личность.')).toBeInTheDocument();

    bid();
    expect(await screen.findByText('Подтверждение личности пройдено.')).toBeInTheDocument();
    expect(screen.queryByText('Чтобы делать ставки, подтвердите личность.')).toBeNull();
  });

  it('a successful bid clears the trusted notice', async () => {
    axiosPost.mockRejectedValueOnce(TRUST).mockResolvedValueOnce({ data: {} });
    await renderLot('ACTIVE');
    bid();
    expect(await screen.findByText('Подтверждение личности пройдено.')).toBeInTheDocument();

    bid();
    expect(await screen.findByText(/принята!/)).toBeInTheDocument();
    expect(screen.queryByText('Подтверждение личности пройдено.')).toBeNull();
  });
});
