import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/**
 * FE-KYC-01: bids and Buy Now are behind VerifiedIdentityGate (403 IDENTITY_VERIFICATION_REQUIRED),
 * then the trusted-participant gate (403, Russian reason, no code), then the deposit check (400,
 * Russian reason). Only the KYC refusal gets the identity-tab guidance; the others show the backend
 * reason as it is - no invented call to action.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

const KYC = httpError(403, { status: 403, error: 'Forbidden', code: 'IDENTITY_VERIFICATION_REQUIRED',
  message: 'Операция доступна только после подтверждения личности (KYC). Пройдите верификацию личности.' });
const TRUST = httpError(403, { status: 403, error: 'Forbidden',
  message: 'Участие в торгах доступно только доверенным пользователям. Обратитесь к администратору для получения доступа.' });
const DEPOSIT = httpError(400, { status: 400, error: 'Некорректный запрос', message: 'Недостаточно средств на депозите. Требуется: 50000.00' });

const lot = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status, startingPrice: 1000000, currentPrice: 1000000, minBidStep: 10000, minimumNextBid: 1010000,
  buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000, ...extra,
});

async function renderLot(status: string, extra: Record<string, unknown> = {}) {
  axiosGet.mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: lot(status, extra) }) : Promise.resolve({ data: {} }));
  const router = createMemoryRouter([
    { path: '/auction/:id', element: <AuctionPage /> },
    { path: '/profile/identity', element: <p>IDENTITY TAB</p> },
  ], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

async function bid() {
  fireEvent.change(screen.getByPlaceholderText('1 010 000'), { target: { value: '1010000' } });
  fireEvent.click(screen.getByRole('button', { name: 'Ставка' }));
}

beforeEach(() => {
  axiosGet.mockReset();
  axiosPost.mockReset();
  useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 5, username: 'buyer', email: 'b@test.com', roles: ['ROLE_USER'] } });
});
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('FE-KYC-01: lot page gate guidance', () => {
  it('a bid refused for KYC shows a lasting notice that leads to the identity tab', async () => {
    axiosPost.mockRejectedValue(KYC);
    await renderLot('ACTIVE');
    await bid();
    const notice = await screen.findByText('Чтобы делать ставки, подтвердите личность.');
    expect(notice.closest('[role=alert]')).toHaveTextContent('Пройдите верификацию личности');
    // UX-KYC-01: trading also needs the trusted status - stated as a rule, never as the user's status
    expect(notice.closest('[role=alert]')).toHaveTextContent('статус доверенного пользователя — его выдаёт администратор отдельно');
    fireEvent.click(screen.getByRole('link', { name: 'Пройти подтверждение личности' }));
    expect(await screen.findByText('IDENTITY TAB')).toBeInTheDocument();
  });

  it('Buy Now refused for KYC shows the notice for Buy Now', async () => {
    axiosPost.mockRejectedValue(KYC);
    await renderLot('CREATED', { buyNowPrice: 2000000 });
    fireEvent.click(screen.getByRole('button', { name: /Купить сейчас/ }));
    expect((await screen.findByText('Чтобы купить по цене Buy Now, подтвердите личность.')).closest('[role=alert]'))
      .toHaveTextContent('статус доверенного пользователя — его выдаёт администратор отдельно');
    expect(screen.getByRole('link', { name: 'Пройти подтверждение личности' })).toHaveAttribute('href', '/profile/identity');
  });

  it('a successful bid after verification clears the notice', async () => {
    axiosPost.mockRejectedValueOnce(KYC).mockResolvedValueOnce({ data: {} });
    await renderLot('ACTIVE');
    await bid();
    await screen.findByText('Чтобы делать ставки, подтвердите личность.');
    await bid();
    await waitFor(() => expect(screen.queryByText('Чтобы делать ставки, подтвердите личность.')).not.toBeInTheDocument());
  });
});

describe('FE-KYC-01 adversarial: other refusals are not dressed up as KYC', () => {
  it.each([
    ['trusted gate (403 without code)', TRUST, 'доступно только доверенным пользователям'],
    ['insufficient deposit (400)', DEPOSIT, 'Недостаточно средств на депозите. Требуется: 50000.00'],
    ['generic 403 English', httpError(403, { error: 'Forbidden', message: 'Access is denied' }), 'Недостаточно прав для этого действия.'],
    ['409 lot state', httpError(409, { message: 'Аукцион не активен' }), 'Аукцион не активен'],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером'],
  ])('%s -> its own message, no identity link', async (_n, error, text) => {
    axiosPost.mockRejectedValue(error);
    await renderLot('ACTIVE');
    await bid();
    expect(await screen.findByText(new RegExp(text))).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Пройти подтверждение личности' })).not.toBeInTheDocument();
  });

  it('the KYC code wins even with another status (code is authoritative)', async () => {
    axiosPost.mockRejectedValue(httpError(409, { code: 'IDENTITY_VERIFICATION_REQUIRED', message: 'Требуется KYC' }));
    await renderLot('ACTIVE');
    await bid();
    expect(await screen.findByText('Чтобы делать ставки, подтвердите личность.')).toBeInTheDocument();
  });
});
