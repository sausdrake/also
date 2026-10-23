import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import { useAuthStore } from '../store/authStore';

/**
 * VIN-AUTH-01: the server decides who sees the VIN; the page only renders what it gets, sends the viewer's
 * token with the public vehicle request and says why the VIN is missing.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: { subscribed: false } })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;

const lot = {
  id: 1, vehicleId: 9, vehicleInfo: 'Toyota Camry 2019', status: 'ACTIVE', startingPrice: 1000000, currentPrice: 1000000,
  minBidStep: 10000, minimumNextBid: 1010000, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z',
  endTime: '2099-12-31T00:00:00Z', totalBids: 0, requiredDepositAmount: 50000,
};
const vehicle = (vin: string | null, vinHidden: boolean) => ({
  id: 9, brand: 'Toyota', model: 'Camry', year: 2019, vin, vinHidden,
});

function serve(vin: string | null, vinHidden: boolean, failWithToken = false) {
  axiosGet.mockImplementation((url: unknown, config?: { headers?: Record<string, string> }) => {
    const u = String(url);
    if (u.includes('/api/public/auctions/1')) return Promise.resolve({ data: lot });
    if (u.includes('/api/v1/public/vehicles/9')) {
      if (failWithToken && config?.headers?.Authorization) return Promise.reject(new Error('401'));
      return Promise.resolve({ data: vehicle(vin, vinHidden) });
    }
    return Promise.resolve({ data: {} });
  });
}

async function renderLot() {
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

beforeEach(() => axiosGet.mockReset());
afterEach(() => useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null }));

describe('VIN-AUTH-01: VIN on the lot page', () => {
  it('withheld VIN: no VIN shown, the reason is', async () => {
    serve(null, true);
    await renderLot();
    expect((await screen.findAllByText('VIN доступен после подтверждения личности')).length).toBeGreaterThan(0);
    expect(screen.queryByText(/VIN: /)).not.toBeInTheDocument();
  });

  it('verified viewer: the VIN the server sent is shown, and the token went with the request', async () => {
    useAuthStore.setState({ isAuthenticated: true, accessToken: 'tok', user: { id: 5, username: 'b', email: 'b@t', roles: ['ROLE_USER'] } });
    serve('JTNB11HK503012345', false);
    await renderLot();
    expect((await screen.findAllByText('VIN: JTNB11HK503012345')).length).toBeGreaterThan(0);
    const call = axiosGet.mock.calls.find(c => String(c[0]).includes('/api/v1/public/vehicles/9'));
    expect(call?.[1]?.headers?.Authorization).toBe('Bearer tok');
  });

  it('a refused token (expired) still loads the card anonymously', async () => {
    useAuthStore.setState({ isAuthenticated: true, accessToken: 'expired', user: { id: 5, username: 'b', email: 'b@t', roles: ['ROLE_USER'] } });
    serve(null, true, true);
    await renderLot();
    await waitFor(() => expect(screen.getAllByText('VIN доступен после подтверждения личности').length).toBeGreaterThan(0));
  });
});
