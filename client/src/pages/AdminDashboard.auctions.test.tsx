import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * Protects AuctionsPage's detail-panel participant fencing (same defect class as MVP-003). The
 * participant list shows whose deposits are frozen and drives the force-unfreeze decision, so an
 * earlier auction's participants must never appear in another auction's panel or reopen data
 * after the panel was closed. AuctionsPage is rendered through AdminDashboard's own routing.
 */

vi.mock('../api/client', () => ({
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

type Participant = { userId: number; username: string; frozenAmount: number; isWinner: boolean };

const auction = (id: number) => ({
  id, vehicleInfo: `Vehicle ${id}`, status: 'COMPLETED', vin: `VIN-${id}`, currentPrice: 1000,
  startTime: '2026-01-01T10:00:00', endTime: '2026-01-02T10:00:00', confirmedStart: true,
});
const participant = (name: string): Participant => ({ userId: 1, username: name, frozenAmount: 500, isWinner: false });

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** `participants` maps an auction id to the queue of responses its successive GETs return. */
function mockApi(participants: Record<number, Array<Promise<{ data: Participant[] }>>>) {
  apiGet.mockImplementation((url: string) => {
    if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [auction(1), auction(2)] } });
    const match = /^\/admin\/auctions\/(\d+)\/participants$/.exec(url);
    if (match) {
      const next = participants[Number(match[1])]?.shift();
      if (next) return next;
      throw new Error(`unexpected participants request ${url}`);
    }
    return Promise.resolve({ data: [] });
  });
}

async function renderAuctionsPage() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/auctions'] }
  );
  render(<RouterProvider router={router} />);
  return screen.findAllByText('Подробнее');
}

async function settle(action: () => void, promise: Promise<unknown>) {
  await act(async () => {
    action();
    await promise.catch(() => undefined);
    await Promise.resolve();
  });
}

const closeButton = () => screen.getByText('✕');

describe('AdminDashboard AuctionsPage - stale participants fencing', () => {
  it('participants of an earlier auction never appear in the panel of the auction opened after it', async () => {
    const requestA = deferred<{ data: Participant[] }>();
    const requestB = deferred<{ data: Participant[] }>();
    mockApi({ 1: [requestA.promise], 2: [requestB.promise] });

    const details = await renderAuctionsPage();
    fireEvent.click(details[0]);
    fireEvent.click(closeButton());
    fireEvent.click(details[1]);
    expect(screen.getByText('Аукцион #2')).toBeInTheDocument();

    await settle(() => requestB.resolve({ data: [participant('bidder-of-2')] }), requestB.promise);
    expect(screen.getByText('bidder-of-2')).toBeInTheDocument();

    await settle(() => requestA.resolve({ data: [participant('bidder-of-1')] }), requestA.promise);
    expect(screen.getByText('bidder-of-2')).toBeInTheDocument();
    expect(screen.queryByText('bidder-of-1')).not.toBeInTheDocument();
  });

  it('a stale response cannot end the loading state of the newer request', async () => {
    const requestA = deferred<{ data: Participant[] }>();
    const requestB = deferred<{ data: Participant[] }>();
    mockApi({ 1: [requestA.promise], 2: [requestB.promise] });

    const details = await renderAuctionsPage();
    fireEvent.click(details[0]);
    fireEvent.click(closeButton());
    fireEvent.click(details[1]);

    await settle(() => requestA.resolve({ data: [participant('bidder-of-1')] }), requestA.promise);
    expect(screen.getByText('Загрузка...')).toBeInTheDocument();
    expect(screen.queryByText('bidder-of-1')).not.toBeInTheDocument();

    await settle(() => requestB.resolve({ data: [participant('bidder-of-2')] }), requestB.promise);
    expect(screen.getByText('bidder-of-2')).toBeInTheDocument();
  });

  it('a force-unfreeze refresh for an auction the admin left does not overwrite the open one', async () => {
    const staleRefresh = deferred<{ data: Participant[] }>();
    const requestB = deferred<{ data: Participant[] }>();
    mockApi({
      1: [Promise.resolve({ data: [participant('bidder-of-1')] }), staleRefresh.promise],
      2: [requestB.promise],
    });
    apiPost.mockResolvedValue({ data: { message: 'ok' } });

    const details = await renderAuctionsPage();
    fireEvent.click(details[0]);
    await screen.findByText('bidder-of-1');

    fireEvent.click(screen.getByText('Принудительно разморозить все (не-победители)'));
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/admin/auctions/1/participants'));
    await waitFor(() => expect(apiGet.mock.calls.filter(c => c[0] === '/admin/auctions/1/participants')).toHaveLength(2));

    fireEvent.click(closeButton());
    fireEvent.click(details[1]);
    await settle(() => requestB.resolve({ data: [participant('bidder-of-2')] }), requestB.promise);

    await settle(() => staleRefresh.resolve({ data: [participant('bidder-of-1-refreshed')] }), staleRefresh.promise);
    expect(screen.getByText('Аукцион #2')).toBeInTheDocument();
    expect(screen.getByText('bidder-of-2')).toBeInTheDocument();
    expect(screen.queryByText('bidder-of-1-refreshed')).not.toBeInTheDocument();
  });

  it('normal flow: participants load, force-unfreeze refreshes the open auction', async () => {
    mockApi({
      1: [Promise.resolve({ data: [participant('bidder-of-1')] }), Promise.resolve({ data: [participant('after-unfreeze')] })],
    });
    apiPost.mockResolvedValue({ data: { message: 'Разморозка выполнена' } });

    const details = await renderAuctionsPage();
    fireEvent.click(details[0]);
    expect(await screen.findByText('bidder-of-1')).toBeInTheDocument();

    fireEvent.click(screen.getByText('Принудительно разморозить все (не-победители)'));
    expect(await screen.findByText('after-unfreeze')).toBeInTheDocument();
    expect(apiPost).toHaveBeenCalledWith('/admin/auctions/1/force-unfreeze-all');
  });
});

describe('BL-22: auction statuses are shown with the domain wording, closed ones included', () => {
  const lots = [
    { ...auction(1), status: 'WAITING_FOR_PAYMENT', vehicleInfo: 'Lot WFP' },
    { ...auction(2), status: 'SALE_FAILED', vehicleInfo: 'Lot SF' },
    { ...auction(3), status: 'ACTIVE', vehicleInfo: 'Lot A' },
  ];

  async function renderList() {
    apiGet.mockImplementation((url: string) => url.startsWith('/admin/auctions?')
      ? Promise.resolve({ data: { content: lots } }) : Promise.resolve({ data: [] }));
    const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
    render(<RouterProvider router={router} />);
    await screen.findByText('Lot WFP');
  }

  it('rows show the label, never the enum', async () => {
    await renderList();
    const row = (name: string) => screen.getByText(name).closest('tr')!;
    expect(row('Lot WFP')).toHaveTextContent('Ожидание оплаты покупателем');
    expect(row('Lot SF')).toHaveTextContent('Сделка не состоялась');
    expect(row('Lot A')).toHaveTextContent('Идут торги');
    expect(document.body.textContent).not.toMatch(/WAITING_FOR_PAYMENT|SALE_FAILED/);
  });

  it('a closed status has its own filter tab', async () => {
    await renderList();
    fireEvent.click(screen.getByRole('button', { name: 'Ожидание оплаты покупателем (1)' }));
    expect(screen.getByText('Lot WFP')).toBeInTheDocument();
    expect(screen.queryByText('Lot A')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Сделка не состоялась (1)' })).toBeInTheDocument();
  });

  it('the detail panel shows the label', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: lots } });
      if (/\/admin\/auctions\/\d+\/participants$/.test(url)) return Promise.resolve({ data: [] });
      return Promise.resolve({ data: [] });
    });
    const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
    render(<RouterProvider router={router} />);
    const row = (await screen.findByText('Lot SF')).closest('tr')!;
    fireEvent.click(within(row).getByText('Подробнее'));
    // the panel's field is a <p> label inside its card (the table header "Статус" is a <th>)
    const status = (await screen.findAllByText('Статус')).find(e => e.tagName === 'P')!;
    expect(status.parentElement).toHaveTextContent('Сделка не состоялась');
  });

  it('the dashboard feed shows the label for auctions', async () => {
    apiGet.mockImplementation((url: string) => url.startsWith('/admin/auctions?')
      ? Promise.resolve({ data: { content: [lots[0]] } }) : Promise.resolve({ data: { content: [] } }));
    const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin'] });
    render(<RouterProvider router={router} />);
    const item = (await screen.findByText('Аукцион #1: Lot WFP')).parentElement!;
    expect(item).toHaveTextContent('Ожидание оплаты покупателем');
  });
});
