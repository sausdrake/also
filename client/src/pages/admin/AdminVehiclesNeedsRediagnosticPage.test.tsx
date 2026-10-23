import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AdminVehiclesNeedsRediagnosticPage } from './AdminVehiclesNeedsRediagnosticPage';
import api from '../../api/client';

/** MVP-005: ordering a re-diagnostic (mandatory reason) and reading the diagnostic history. */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const vehicle = { id: 7, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001', licensePlate: null,
  status: 'NEEDS_REDIAGNOSTIC', diagnosticId: null, diagnosticName: null, updatedAt: '2026-10-15T00:00:00Z' };

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/admin/vehicles/needs-rediagnostic') return Promise.resolve({ data: [vehicle] });
    if (url === '/admin/vehicles/7/diagnostic-history') return Promise.resolve({ data: [
      { id: 11, status: 'APPROVED', createdAt: '2026-09-01T00:00:00Z', previousDiagnosticRequestId: null,
        rediagnosticReason: null, rediagnosticComment: null, rediagnosticOrderedAt: null },
      { id: 12, status: 'PENDING', createdAt: '2026-10-15T00:00:00Z', previousDiagnosticRequestId: 11,
        rediagnosticReason: 'SELLER_FAULT', rediagnosticComment: 'скрыл ДТП', rediagnosticOrderedAt: '2026-10-15T00:00:00Z' },
    ] });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: { id: 12 } });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openForm() {
  render(<AdminVehiclesNeedsRediagnosticPage />);
  fireEvent.click(await screen.findByText('Назначить повторную диагностику'));
  return screen.getByTestId('order-form-7');
}

describe('AdminVehiclesNeedsRediagnosticPage (MVP-005)', () => {
  it('a reason is mandatory before the order can be sent', async () => {
    const form = await openForm();
    expect(within(form).getByText('Назначить')).toBeDisabled();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('sends the chosen reason and comment and reports the new request', async () => {
    const form = await openForm();
    fireEvent.click(within(form).getByText('Нарушение продавца'));
    fireEvent.change(within(form).getByLabelText('Комментарий'), { target: { value: '  скрыл ДТП ' } });
    fireEvent.click(within(form).getByText('Назначить'));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/vehicles/7/rediagnostic',
      { reason: 'SELLER_FAULT', comment: 'скрыл ДТП' }));
    expect(await screen.findByText('Повторная диагностика назначена: заявка #12')).toBeInTheDocument();
  });

  it('AUC-NOSALE-01: offers the no-sale reason for a vehicle whose auction ended without a sale', async () => {
    const form = await openForm();
    fireEvent.click(within(form).getByText('Аукцион без продажи'));
    fireEvent.click(within(form).getByText('Назначить'));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/vehicles/7/rediagnostic',
      { reason: 'NO_SALE', comment: null }));
  });

  it('shows the server refusal (e.g. reason not matching the sale fault)', async () => {
    apiPost.mockRejectedValueOnce({ response: { data: { message: 'Причина SELLER_FAULT не соответствует решению по аукциону 3' } } });
    const form = await openForm();
    fireEvent.click(within(form).getByText('Нарушение продавца'));
    fireEvent.click(within(form).getByText('Назначить'));

    expect(await screen.findByText(/не соответствует решению по аукциону/)).toBeInTheDocument();
  });

  it('shows the diagnostic history with the re-diagnostic link and reason', async () => {
    render(<AdminVehiclesNeedsRediagnosticPage />);
    fireEvent.click(await screen.findByText('История диагностик'));

    const history = await screen.findByTestId('history-7');
    expect(await within(history).findByText(/Заявка #11/)).toBeInTheDocument();
    expect(within(history).getByText(/Повторная диагностика заявки #11: нарушение продавца — скрыл ДТП/)).toBeInTheDocument();
  });

  it('shows the vehicle and request statuses as labels, never as raw enums', async () => {
    render(<AdminVehiclesNeedsRediagnosticPage />);
    expect(await screen.findByText('Требует повторной диагностики')).toBeInTheDocument();
    expect(screen.queryByText('NEEDS_REDIAGNOSTIC')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('История диагностик'));
    const history = await screen.findByTestId('history-7');
    await within(history).findByText(/Заявка #11/);
    const items = within(history).getAllByRole('listitem').map(li => li.textContent ?? '');
    expect(items[0]).toContain('Заявка #11 · Одобрено');
    expect(items[1]).toContain('Заявка #12 · На рассмотрении');
    expect(items.join(' ')).not.toMatch(/APPROVED|PENDING/);
  });
});
