import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/**
 * G1: an anonymous visitor asked to sign in on a lot (the "Войдите" link, a bid, Buy Now) comes back
 * to the lot - the login form receives the lot as its return path. No request is sent anonymously.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

const lot = (extra: Record<string, unknown>) => ({
  id: 1, vehicleId: 9, vehicleInfo: 'Toyota Camry 2019', status: 'ACTIVE', startingPrice: 1000000, currentPrice: 1000000,
  minBidStep: 10000, minimumNextBid: 1010000, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z',
  endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000, ...extra,
});

function serve(extra: Record<string, unknown> = {}) {
  axiosGet.mockImplementation((url: unknown) => {
    const u = String(url);
    if (u.includes('/api/public/auctions/1')) return Promise.resolve({ data: lot(extra) });
    if (u.includes('/api/v1/public/vehicles/9')) return Promise.resolve({ data: { id: 9, brand: 'Toyota', model: 'Camry', year: 2019, vin: null, vinHidden: true } });
    return Promise.resolve({ data: {} });
  });
}

async function renderLot() {
  const router = createMemoryRouter([
    { path: '/auction/:id', element: <AuctionPage /> },
    { path: '/login', element: <p>LOGIN FORM</p> },
  ], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
  return router;
}

beforeEach(() => {
  axiosGet.mockReset();
  axiosPost.mockReset();
  useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null } as never);
});

describe('G1: sign-in from a lot returns to the lot', () => {
  it('"Войдите" opens the login form with the lot as the return path', async () => {
    serve();
    const router = await renderLot();
    fireEvent.click(screen.getByRole('button', { name: 'Войдите' }));
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: { pathname: '/auction/1' } });
  });

  it('Buy Now while anonymous: the login form with the lot as the return path, nothing is sent', async () => {
    serve({ status: 'CREATED', buyNowPrice: 2000000, startTime: '2099-01-01T00:00:00Z' });
    const router = await renderLot();
    fireEvent.click(await screen.findByRole('button', { name: /Купить сейчас/ }));
    expect(router.state.location.pathname).toBe('/login');
    expect(router.state.location.state).toEqual({ from: { pathname: '/auction/1' } });
    expect(axiosPost).not.toHaveBeenCalled();
  });
});
