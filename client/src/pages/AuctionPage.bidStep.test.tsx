import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/**
 * AUCTION-01 (BD-02..BD-04): the lot page shows and enforces the server's own minimum next bid
 * (minimumNextBid) - it never adds the step itself - and only sends money amounts (2 decimals, within
 * the technical capacity). After a live bid it asks the server again instead of computing.
 */

const ws = vi.hoisted(() => ({ push: (() => {}) as (message: unknown) => void }));
vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', async () => {
  const React = await import('react');
  return {
    useWebSocket: () => {
      const [lastMessage, setLastMessage] = React.useState<unknown>(null);
      ws.push = setLastMessage;
      return { isConnected: true, lastMessage };
    },
  };
});

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

// 123.45 -> step 1.235 -> threshold 124.685 -> the server says 124.69
const lot = (extra: Record<string, unknown> = {}) => ({
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status: 'ACTIVE', startingPrice: 123.45, currentPrice: 123.45,
  minBidStep: 1.235, minimumNextBid: 124.69, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z',
  endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000, ...extra,
});

let current = lot();

async function renderLot() {
  axiosGet.mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: current }) : Promise.resolve({ data: {} }));
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

const bidInput = () => screen.getByRole('spinbutton') as HTMLInputElement;
const bid = (value: string) => {
  fireEvent.change(bidInput(), { target: { value } });
  fireEvent.click(screen.getByRole('button', { name: 'Ставка' }));
};

beforeEach(() => {
  current = lot();
  axiosGet.mockReset();
  axiosPost.mockReset().mockResolvedValue({ data: {} });
  useAuthStore.setState({ isAuthenticated: true, accessToken: 't', user: { id: 5, username: 'buyer', email: 'b@test.com', roles: ['ROLE_USER'] } });
});
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('AUCTION-01: minimum next bid on the lot page', () => {
  it('shows the server minimum and the 3-decimal step; the input is prefilled with that minimum', async () => {
    await renderLot();
    expect(screen.getByTestId('min-next-bid')).toHaveTextContent(/124,69 сом/);
    expect(screen.getByText(/1,235 сом/)).toBeInTheDocument();
    expect(bidInput().value).toBe('124.69');
  });

  it.each([
    ['124.68', /Минимальная ставка: 124,69 сом/],
    ['123.45', /Минимальная ставка: 124,69 сом/],
    ['124.685', /Не больше 2 знаков после запятой/],
    ['0', /Сумма должна быть больше 0/],
    ['100000000', /Максимум: 99 999 999.99 сом/],
  ])('%s is refused on the page and nothing is sent', async (value, error) => {
    await renderLot();
    bid(value);
    expect(await screen.findByText(error)).toBeInTheDocument();
    expect(axiosPost).not.toHaveBeenCalled();
  });

  it('exactly the server minimum is sent as a number', async () => {
    await renderLot();
    bid('124.69');
    await waitFor(() => expect(axiosPost).toHaveBeenCalledTimes(1));
    expect(axiosPost.mock.calls[0][1]).toEqual({ amount: 124.69 });
  });

  it('no minimum from the server: nothing is computed on the page, the server stays the judge', async () => {
    current = lot({ minimumNextBid: undefined });
    await renderLot();
    expect(screen.getByTestId('min-next-bid')).toHaveTextContent('—');
    expect(bidInput().value).toBe('');
    bid('124.00');
    await waitFor(() => expect(axiosPost).toHaveBeenCalledTimes(1));
  });

  it('a live bid makes the page ask the server for the new minimum instead of adding the step', async () => {
    await renderLot();
    const lotFetches = () => axiosGet.mock.calls.filter(c => String(c[0]).includes('/api/public/auctions/1')).length;
    const before = lotFetches();
    current = lot({ currentPrice: 124.69, minimumNextBid: 125.93, totalBids: 1 });
    act(() => ws.push({ messageType: 'BID_PLACED', currentPrice: 124.69, totalBids: 1 }));
    await waitFor(() => expect(lotFetches()).toBeGreaterThan(before));
    await waitFor(() => expect(screen.getByTestId('min-next-bid')).toHaveTextContent(/125,93 сом/));
    expect(bidInput().value).toBe('125.93');
  });
});
