import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminSalePage, type SaleStatus } from './AdminSalePage';
import api from '../../api/client';

/**
 * Phase 88: protects AdminSalePage's request-generation fencing. The page is not remounted
 * when the admin navigates between two different /admin/sale/:auctionId routes (React Router
 * reuses the same component instance for a param-only change), so a response for a
 * previously-viewed auction can arrive after a newer auction's request has already been
 * issued. Real route navigation is driven via createMemoryRouter's imperative `navigate`
 * (no remount is simulated), and promise resolution order is controlled explicitly so the
 * tests prove ordering, not timing.
 */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function saleStatus(overrides: { auctionId: number; auctionStatus: string }): SaleStatus {
  return {
    auctionId: overrides.auctionId,
    auctionStatus: overrides.auctionStatus,
    paymentDeadline: null,
    overdue: false,
    paymentEvidence: null,
    handoverEvidence: null,
    saleResolution: null,
    settlement: null,
    settlementCorrections: [],
  };
}

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

function renderAtAuction(initialId: number) {
  const router = createMemoryRouter(
    [{ path: '/admin/sale/:auctionId', element: <AdminSalePage /> }],
    { initialEntries: [`/admin/sale/${initialId}`] }
  );
  const view = render(<RouterProvider router={router} />);
  return { router, ...view };
}

describe('AdminSalePage - stale cross-auction response fencing', () => {
  it('Scenario 1 - a stale response for a previously-viewed auction cannot overwrite the currently-viewed auction', async () => {
    const requestA = deferred<{ data: SaleStatus }>();
    const requestB = deferred<{ data: SaleStatus }>();
    apiGet.mockImplementation((url: string) => {
      if (url === '/admin/sale/1') return requestA.promise;
      if (url === '/admin/sale/2') return requestB.promise;
      return Promise.resolve({ data: [] });
    });

    const { router } = renderAtAuction(1);

    // Initial mount fetch for auction 1 is now in flight (request A), deliberately unresolved.
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/admin/sale/1'));

    // Navigate to auction 2 WITHOUT remounting the page - the route param changes, the same
    // AdminSalePage instance re-renders, and a new fetch (request B) is issued.
    await act(async () => {
      router.navigate('/admin/sale/2');
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/admin/sale/2'));

    // Resolve the CURRENT request (B) first - this is what should end up on screen.
    requestB.resolve({ data: saleStatus({ auctionId: 2, auctionStatus: 'SOLD' }) });
    await screen.findByText('SOLD');

    // Prove the stale A response is still capable of mutating state by resolving it now, after B.
    requestA.resolve({ data: saleStatus({ auctionId: 1, auctionStatus: 'WAITING_FOR_PAYMENT' }) });
    await waitFor(() => expect(requestA.promise).resolves.toBeDefined());

    // The guard must have discarded the stale A response - B's status remains displayed.
    expect(screen.getByText('SOLD')).toBeInTheDocument();
    expect(screen.queryByText('WAITING_FOR_PAYMENT')).not.toBeInTheDocument();
  });

  it('Scenario 2 - a stale refresh triggered by a mutation\'s onDone cannot overwrite a subsequently-viewed auction', async () => {
    let auction1GetCount = 0;
    const staleRefetch = deferred<{ data: SaleStatus }>();
    const requestB = deferred<{ data: SaleStatus }>();
    apiGet.mockImplementation((url: string) => {
      if (url === '/admin/sale/1') {
        auction1GetCount += 1;
        // First call: the page's own initial mount fetch, resolved immediately so the
        // deadline-extend form (only shown while WAITING_FOR_PAYMENT) is available to interact
        // with. Second call: the refresh triggered by that form's onDone - held pending as the
        // "stale" request this test protects against.
        if (auction1GetCount === 1) {
          return Promise.resolve({ data: saleStatus({ auctionId: 1, auctionStatus: 'WAITING_FOR_PAYMENT' }) });
        }
        return staleRefetch.promise;
      }
      if (url === '/admin/sale/2') return requestB.promise;
      return Promise.resolve({ data: [] });
    });
    apiPost.mockResolvedValue({ data: {} });

    const { router, container } = renderAtAuction(1);

    await screen.findByText('Изменить платёжный дедлайн');

    fireEvent.change(screen.getByPlaceholderText('Причина (обязательно)'), { target: { value: 'test reason' } });
    // The datetime-local input has no accessible label/placeholder in the actual component -
    // this is the narrowest selector available; there is no accessible alternative here.
    const datetimeInput = container.querySelector('input[type="datetime-local"]') as HTMLInputElement;
    fireEvent.change(datetimeInput, { target: { value: '2027-01-01T12:00' } });
    fireEvent.click(screen.getByText('Изменить дедлайн'));

    // The mutation's own POST resolves quickly and calls onDone() -> refresh() -> a second,
    // still-auction-1 GET (the stale request), which we deliberately hold pending.
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/sale/1/deadline/extend', expect.any(Object)));
    await waitFor(() => expect(auction1GetCount).toBe(2));

    // While that stale auction-1 refresh is still pending, navigate to auction 2 - WITHOUT
    // remounting the page.
    await act(async () => {
      router.navigate('/admin/sale/2');
    });
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/admin/sale/2'));

    requestB.resolve({ data: saleStatus({ auctionId: 2, auctionStatus: 'SOLD' }) });
    await screen.findByText('SOLD');

    staleRefetch.resolve({ data: saleStatus({ auctionId: 1, auctionStatus: 'WAITING_FOR_PAYMENT' }) });
    await waitFor(() => expect(staleRefetch.promise).resolves.toBeDefined());

    expect(screen.getByText('SOLD')).toBeInTheDocument();
    expect(screen.queryByText('WAITING_FOR_PAYMENT')).not.toBeInTheDocument();
  });
});
