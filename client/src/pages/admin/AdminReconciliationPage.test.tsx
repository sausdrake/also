import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { AdminReconciliationPage } from './AdminReconciliationPage';
import api from '../../api/client';
import type { ReconciliationCaseListItem } from '../../components/admin/reconciliation/ReconciliationCaseList';
import type { ReconciliationCaseDetailResponse } from '../../components/admin/reconciliation/ReconciliationCaseDetail';

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

/**
 * Phase 82 (G-77-9 / G-80-3): protects the Phase 66 request-fencing fix in
 * AdminReconciliationPage - a stale, out-of-order detail response (or one that arrives after
 * the viewed case was resolved) must never overwrite state that is no longer current, even
 * though nothing in the component tree ever cancels the underlying network request itself.
 *
 * Promise resolution order is controlled explicitly via `deferred()` rather than relying on
 * timers, so the test proves ordering, not timing.
 */

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const listItem = (id: number, type: string, status = 'OPEN'): ReconciliationCaseListItem => ({
  id,
  paymentId: 100 + id,
  paymentExists: true,
  type,
  status,
  openedAt: '2026-06-05T22:45:55Z',
  resolvedAt: null,
  resolvedByAdminId: null,
  requestedAmount: 1000,
  requestedCurrency: 'KGS',
});

const detailFor = (id: number, type: string, status = 'OPEN'): ReconciliationCaseDetailResponse => ({
  reconciliationCase: {
    id,
    paymentId: 100 + id,
    type,
    status,
    openedAt: '2026-06-05T22:45:55Z',
    resolvedByAdminId: null,
    resolutionNote: null,
    resolvedAt: null,
    recurredAfterResolution: null,
  },
  payment: {
    id: 100 + id,
    amountRequested: 1000,
    currency: 'KGS',
    status: 'PENDING',
    providerReference: null,
    providerTransactionId: null,
    amountConfirmed: null,
    lateSuccessAfterExpiry: false,
    createdAt: '2026-06-05T22:45:55Z',
    updatedAt: '2026-06-05T22:45:55Z',
    expiresAt: null,
  },
  evidence: [],
});

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

describe('AdminReconciliationPage - stale detail response fencing', () => {
  it('does not let an earlier-selected case\'s late-arriving response overwrite the later-selected case that is currently displayed', async () => {
    const caseA = listItem(1, 'MISMATCH');
    const caseB = listItem(2, 'DUPLICATE');

    const detailA = deferred<{ data: ReconciliationCaseDetailResponse }>();
    const detailB = deferred<{ data: ReconciliationCaseDetailResponse }>();

    apiGet.mockImplementation((url: string) => {
      if (url === '/admin/reconciliation/cases') {
        return Promise.resolve({ data: [caseA, caseB] });
      }
      if (url === '/admin/reconciliation/cases/1') return detailA.promise;
      if (url === '/admin/reconciliation/cases/2') return detailB.promise;
      throw new Error(`unexpected GET ${url}`);
    });

    render(<AdminReconciliationPage />);

    // Select case A first - its detail request is now in flight but deliberately not resolved.
    fireEvent.click(await screen.findByRole('button', { name: /Несовпадение суммы/ }));

    // Before A resolves, select case B - a newer, still-later request is now also in flight.
    fireEvent.click(screen.getByRole('button', { name: /Дублирующий платёж/ }));

    // Resolve the CURRENT (B) request first - this is what should end up on screen.
    detailB.resolve({ data: detailFor(2, 'DUPLICATE') });
    await screen.findByText(/Дело #2/);

    // Prove the stale A response is still capable of mutating state by resolving it now, after B.
    detailA.resolve({ data: detailFor(1, 'MISMATCH') });

    // Give any (incorrect) state update from the stale response a chance to flush.
    await waitFor(() => expect(detailA.promise).resolves.toBeDefined());

    // The guard must have discarded the stale A response - B's detail remains displayed.
    expect(screen.getByText(/Дело #2/)).toBeInTheDocument();
    expect(screen.queryByText(/Дело #1/)).not.toBeInTheDocument();
  });

  it('does not let a detail request that was in flight before a case was resolved resurrect its data afterwards', async () => {
    const caseA = listItem(1, 'MISMATCH');
    const caseB = listItem(2, 'DUPLICATE');

    const detailB = deferred<{ data: ReconciliationCaseDetailResponse }>();
    const resolveAction = deferred<{ data: unknown }>();

    apiGet.mockImplementation((url: string) => {
      if (url === '/admin/reconciliation/cases') {
        return Promise.resolve({ data: [caseA, caseB] });
      }
      if (url === '/admin/reconciliation/cases/1') {
        return Promise.resolve({ data: detailFor(1, 'MISMATCH') });
      }
      if (url === '/admin/reconciliation/cases/2') return detailB.promise;
      throw new Error(`unexpected GET ${url}`);
    });
    apiPost.mockImplementation(() => resolveAction.promise);

    render(<AdminReconciliationPage />);

    // Select and load case A (resolves immediately) so its resolve controls are on screen.
    fireEvent.click(await screen.findByRole('button', { name: /Несовпадение суммы/ }));
    await screen.findByText(/Дело #1/);

    // Begin resolving case A - the POST is now in flight but deliberately not settled yet.
    fireEvent.click(screen.getByText('Закрыть дело'));
    fireEvent.click(screen.getByText('Подтвердить закрытие'));

    // While A's resolve is still pending, select case B - its detail request is now also in
    // flight and will still be unresolved when the action below completes.
    fireEvent.click(screen.getByRole('button', { name: /Дублирующий платёж/ }));

    // Settle the resolve action - onActioned() fires, clearing detail and invalidating every
    // in-flight generation, including case B's still-pending request above.
    resolveAction.resolve({ data: {} });
    await waitFor(() => expect(apiPost).toHaveBeenCalled());

    // Now let the stale, pre-action case B response arrive.
    detailB.resolve({ data: detailFor(2, 'DUPLICATE') });
    await waitFor(() => expect(detailB.promise).resolves.toBeDefined());

    // The now-invalidated B response must never have been allowed to populate the screen.
    expect(screen.queryByText(/Дело #2/)).not.toBeInTheDocument();
  });
});
