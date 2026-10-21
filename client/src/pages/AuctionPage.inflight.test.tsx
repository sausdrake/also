import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/**
 * FE-INFLIGHT-01 (BL-16): one bid / Buy Now POST at a time. A double tap used to send two requests;
 * the backend refused the second, and that refusal replaced the success message.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, message: string) =>
  Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data: { status, message } } });

const lot = (status: string, extra: Record<string, unknown> = {}) => ({
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status, startingPrice: 1000000, currentPrice: 1000000, minBidStep: 10000, minimumNextBid: 1010000,
  buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000, ...extra,
});

async function renderLot(status: string, extra: Record<string, unknown> = {}) {
  axiosGet.mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: lot(status, extra) }) : Promise.resolve({ data: {} }));
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

/** A POST that stays pending until the test resolves or rejects it. */
function pendingPost() {
  let resolve!: (v: unknown) => void;
  let reject!: (e: unknown) => void;
  axiosPost.mockImplementation(() => new Promise((res, rej) => { resolve = res; reject = rej; }));
  return { resolve: (v: unknown) => resolve(v), reject: (e: unknown) => reject(e) };
}

beforeEach(() => {
  axiosGet.mockReset();
  axiosPost.mockReset();
  useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 5, username: 'buyer', email: 'b@test.com', roles: ['ROLE_USER'] } });
});
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('FE-INFLIGHT-01: bid', () => {
  it('a double tap sends one bid; the button is disabled while it is in flight; success stays', async () => {
    const post = pendingPost();
    await renderLot('ACTIVE');
    fireEvent.change(screen.getByPlaceholderText('1 010 000'), { target: { value: '1010000' } });
    const button = screen.getByRole('button', { name: 'Ставка' });
    // two clicks handled before React re-renders the button (both reach the handler)
    act(() => { button.click(); button.click(); });
    expect(axiosPost).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Отправка…' })).toBeDisabled();
    post.resolve({ data: {} });
    expect(await screen.findByText(/Ставка 1\s010\s000 сом принята!/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Ставка' })).toBeEnabled();
  });

  it('after a refused bid the button is usable again and the next bid is sent', async () => {
    const post = pendingPost();
    await renderLot('ACTIVE');
    fireEvent.change(screen.getByPlaceholderText('1 010 000'), { target: { value: '1010000' } });
    fireEvent.click(screen.getByRole('button', { name: 'Ставка' }));
    post.reject(httpError(400, 'Недостаточно средств на депозите. Требуется: 50000.00'));
    expect(await screen.findByText('Недостаточно средств на депозите. Требуется: 50000.00')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: 'Ставка' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Ставка' }));
    expect(axiosPost).toHaveBeenCalledTimes(2);
  });
});

describe('FE-INFLIGHT-01: Buy Now', () => {
  it('a double tap sends one Buy Now; the button is disabled while it is in flight', async () => {
    const post = pendingPost();
    await renderLot('CREATED', { buyNowPrice: 2000000 });
    const button = screen.getByRole('button', { name: /Купить сейчас за/ });
    // two clicks handled before React re-renders the button (both reach the handler)
    act(() => { button.click(); button.click(); });
    expect(axiosPost).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Отправка…' })).toBeDisabled();
    post.reject(httpError(409, 'Аукцион уже завершён'));
    expect(await screen.findByText('Аукцион уже завершён')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole('button', { name: /Купить сейчас за/ })).toBeEnabled());
  });
});
