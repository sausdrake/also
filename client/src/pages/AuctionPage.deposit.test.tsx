import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';

/** FE-AUCTION-01: the lot shows the deposit the admin set for it (AuctionResponse.requiredDepositAmount). */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({ default: { get: vi.fn(() => Promise.resolve({ data: {} })), post: vi.fn() } }));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;

const auction = {
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status: 'ACTIVE', startingPrice: 1000000, currentPrice: 1000000,
  minBidStep: 10000, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2026-12-31T00:00:00Z',
  confirmedStart: true, photos: [], mainPhotoUrl: null, bidCount: 0,
};

function mockAuction(extra: Record<string, unknown>) {
  axiosGet.mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: { ...auction, ...extra } })
    : Promise.resolve({ data: {} }));
}

async function renderLot() {
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  return (await screen.findByText('Депозит для участия')).nextElementSibling;
}

beforeEach(() => axiosGet.mockReset());

describe('FE-AUCTION-01: lot deposit', () => {
  it('shows the configured deposit', async () => {
    mockAuction({ requiredDepositAmount: 50000 });
    expect(await renderLot()).toHaveTextContent(/^50\s000 сом$/);
  });

  it.each([null, undefined, 0])('a lot with deposit %s says "Не настроен" instead of an amount', async requiredDepositAmount => {
    mockAuction({ requiredDepositAmount });
    const cell = await renderLot();
    expect(cell).toHaveTextContent(/^Не настроен$/);
  });
});
