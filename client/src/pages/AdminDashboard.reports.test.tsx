import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * MVP-003: protects ReportsPage's detail-panel request-generation fencing. ReportsPage is not
 * exported, so it is rendered through AdminDashboard's own routing at
 * /admin/diagnostic/reports. A stale loadDetail() - an earlier "Детали" click, or the
 * SellerViolationResolutionPanel onDone refetch - must never overwrite the currently open
 * report, reopen a closed panel, or show a stale error.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

// The real panel needs violation definitions and a confirm flow; only its onDone contract
// (ReportsPage refetches the open report) matters here.
vi.mock('../components/admin/diagnostic/SellerViolationResolutionPanel', () => ({
  SellerViolationResolutionPanel: ({ onDone }: { onDone: () => void }) => (
    <button onClick={onDone}>mock-violation-done</button>
  ),
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

type TestReport = {
  id: number; brand: string; model: string; year: number; vin: string;
  status: string; createdAt: string; rejectionCategory?: string | null;
};

function report(id: number, overrides: Partial<TestReport> = {}): TestReport {
  return {
    id, brand: `Brand${id}`, model: `Model${id}`, year: 2020, vin: `VIN-${id}`,
    status: 'REJECTED', createdAt: '2026-01-01T10:00:00', rejectionCategory: null,
    ...overrides,
  };
}

const LIST_URL = '/admin/diagnostic/reports?size=200&sort=createdAt,desc';
const detailUrl = (id: number) => `/admin/diagnostic/reports/${id}`;
const header = (id: number) => new RegExp(`Отчёт #${id} · VIN`);
const STALE_ERROR = 'Ошибка загрузки детали';

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** `details` maps a report id to the queue of responses its successive detail GETs return. */
function mockApi(list: TestReport[], details: Record<number, Array<Promise<{ data: TestReport }>>>) {
  apiGet.mockImplementation((url: string) => {
    if (url === LIST_URL) return Promise.resolve({ data: { content: list } });
    const match = /^\/admin\/diagnostic\/reports\/(\d+)$/.exec(url);
    if (match) {
      const next = details[Number(match[1])]?.shift();
      if (next) return next;
      throw new Error(`unexpected detail request ${url}`);
    }
    return Promise.resolve({ data: [] });
  });
}

async function renderReportsPage() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/diagnostic/reports'] }
  );
  render(<RouterProvider router={router} />);
  return screen.findAllByText('Детали');
}

/** Settles a deferred and flushes loadDetail()'s continuation inside act(). */
async function settle(action: () => void, promise: Promise<unknown>) {
  await act(async () => {
    action();
    await promise.catch(() => undefined);
    await Promise.resolve();
  });
}

function panelOf(id: number): HTMLElement {
  return screen.getByText(header(id)).closest('.fixed') as HTMLElement;
}

// The header close button has no accessible name (icon-only) - it is the only button in the
// panel header, the narrowest selector available.
function closeButtonOf(id: number): HTMLElement {
  return screen.getByText(header(id)).parentElement!.parentElement!.querySelector('button') as HTMLElement;
}

describe('AdminDashboard ReportsPage - stale detail response fencing (MVP-003)', () => {
  it('A - a stale response for an earlier "Детали" click cannot replace the newer report', async () => {
    const requestA = deferred<{ data: TestReport }>();
    const requestB = deferred<{ data: TestReport }>();
    mockApi([report(1), report(2)], { 1: [requestA.promise], 2: [requestB.promise] });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    fireEvent.click(detailButtons[1]);
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(detailUrl(2)));

    await settle(() => requestB.resolve({ data: report(2) }), requestB.promise);
    expect(screen.getByText(header(2))).toBeInTheDocument();

    // The stale A response resolves last; without the guard it would replace B.
    await settle(() => requestA.resolve({ data: report(1) }), requestA.promise);
    expect(screen.getByText(header(2))).toBeInTheDocument();
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
  });

  it('B - a stale onDone refetch cannot replace a report opened after it', async () => {
    const staleRefetch = deferred<{ data: TestReport }>();
    const requestB = deferred<{ data: TestReport }>();
    const violated = report(1, { rejectionCategory: 'SELLER_VIOLATION' });
    mockApi([violated, report(2)], {
      1: [Promise.resolve({ data: violated }), staleRefetch.promise],
      2: [requestB.promise],
    });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    await screen.findByText(header(1));

    // SellerViolationResolutionPanel.onDone -> loadDetail(1), held pending (the stale request).
    fireEvent.click(screen.getByText('mock-violation-done'));
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(3));

    // The admin closes the panel (backdrop) and opens report 2.
    fireEvent.click(panelOf(1).querySelector('.bg-black\\/50') as HTMLElement);
    fireEvent.click(detailButtons[1]);
    await settle(() => requestB.resolve({ data: report(2) }), requestB.promise);
    expect(screen.getByText(header(2))).toBeInTheDocument();

    await settle(() => staleRefetch.resolve({ data: violated }), staleRefetch.promise);
    expect(screen.getByText(header(2))).toBeInTheDocument();
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
  });

  it('C - a response arriving after the panel was closed does not reopen it', async () => {
    const lateRefetch = deferred<{ data: TestReport }>();
    const violated = report(1, { rejectionCategory: 'SELLER_VIOLATION' });
    mockApi([violated], { 1: [Promise.resolve({ data: violated }), lateRefetch.promise] });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    await screen.findByText(header(1));

    fireEvent.click(screen.getByText('mock-violation-done'));
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(3));

    fireEvent.click(closeButtonOf(1));
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();

    await settle(() => lateRefetch.resolve({ data: violated }), lateRefetch.promise);
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
    expect(screen.queryByText('mock-violation-done')).not.toBeInTheDocument();
  });

  it('D - a stale request failing after the newer one does not show an error', async () => {
    const requestA = deferred<{ data: TestReport }>();
    const requestB = deferred<{ data: TestReport }>();
    mockApi([report(1), report(2)], { 1: [requestA.promise], 2: [requestB.promise] });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    fireEvent.click(detailButtons[1]);
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith(detailUrl(2)));

    await settle(() => requestB.resolve({ data: report(2) }), requestB.promise);
    await settle(() => requestA.reject(new Error('network')), requestA.promise);

    expect(screen.getByText(header(2))).toBeInTheDocument();
    expect(screen.queryByText(STALE_ERROR)).not.toBeInTheDocument();
  });

  it('D (control) - an error of the current request is still reported', async () => {
    const request = deferred<{ data: TestReport }>();
    mockApi([report(1)], { 1: [request.promise] });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    await settle(() => request.reject(new Error('network')), request.promise);

    expect(screen.getByText(STALE_ERROR)).toBeInTheDocument();
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
  });

  it('E - normal behaviour: open, close, reopen, approve from the panel, onDone refetch', async () => {
    const reviewable = report(1, { status: 'COMPLETED', rejectionCategory: 'SELLER_VIOLATION' });
    const refreshed = { ...reviewable, vin: 'VIN-1-REFRESHED' };
    mockApi([reviewable], {
      1: [
        Promise.resolve({ data: reviewable }),
        Promise.resolve({ data: refreshed }),
        Promise.resolve({ data: reviewable }),
      ],
    });
    apiPost.mockResolvedValue({ data: {} });

    const detailButtons = await renderReportsPage();
    fireEvent.click(detailButtons[0]);
    expect(await screen.findByText(header(1))).toBeInTheDocument();

    // onDone refetch of the open report still applies.
    fireEvent.click(screen.getByText('mock-violation-done'));
    expect(await screen.findByText(/Отчёт #1 · VIN: VIN-1-REFRESHED/)).toBeInTheDocument();

    // Close (X) and reopen.
    fireEvent.click(closeButtonOf(1));
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
    fireEvent.click(detailButtons[0]);
    expect(await screen.findByText(header(1))).toBeInTheDocument();

    // Approve from the panel approves the shown report and closes the panel.
    fireEvent.click(within(panelOf(1)).getByText('Одобрить'));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/diagnostic/reports/1/approve'));
    expect(screen.queryByText(header(1))).not.toBeInTheDocument();
  });
});
