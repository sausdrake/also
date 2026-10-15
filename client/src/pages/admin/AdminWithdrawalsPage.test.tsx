import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AdminWithdrawalsPage } from './AdminWithdrawalsPage';
import api from '../../api/client';

/** F6/F6.1: admin processing of manual payout requests. */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const base = { userId: 5, username: 'seller5', email: 's5@test.com', amount: 3000, createdAt: '2026-10-15T10:00:00Z',
  processingStartedAt: null, processingStartedBy: null, processedAt: null, processedBy: null, rejectionReason: null,
  payoutNote: null };
const pending = { ...base, id: 1, status: 'PENDING' };
const processing = { ...base, id: 2, status: 'PROCESSING', processingStartedAt: '2026-10-15T11:00:00Z', processingStartedBy: 9 };
const paid = { ...processing, id: 3, status: 'PAID', processedAt: '2026-10-15T12:00:00Z', processedBy: 9,
  payoutNote: 'Наличными в офисе, обращение #12' };
const rejected = { ...processing, id: 4, status: 'REJECTED', processedAt: '2026-10-15T12:00:00Z', processedBy: 9,
  rejectionReason: 'Выплата невозможна' };

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue({ data: [pending, processing, paid, rejected] });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AdminWithdrawalsPage (F6/F6.1)', () => {
  it('loads the pending queue first and offers only the actions each status allows', async () => {
    render(<AdminWithdrawalsPage />);
    const p = await screen.findByTestId('withdrawal-1');

    expect(apiGet).toHaveBeenCalledWith('/admin/withdrawals', { params: { status: 'PENDING' } });
    expect(within(p).getByText('В работу')).toBeInTheDocument();
    expect(within(p).getByText('Отклонить')).toBeInTheDocument();
    expect(within(p).queryByText('Выплачено')).not.toBeInTheDocument();

    const pr = screen.getByTestId('withdrawal-2');
    expect(within(pr).getByRole('button', { name: 'Выплачено' })).toBeInTheDocument();
    expect(within(pr).getByRole('button', { name: 'Отклонить' })).toBeInTheDocument();
    expect(within(pr).queryByText('В работу')).not.toBeInTheDocument();

    for (const id of [3, 4]) {
      expect(within(screen.getByTestId(`withdrawal-${id}`)).queryAllByRole('button')).toHaveLength(0);
    }
    expect(within(screen.getByTestId('withdrawal-3')).getByText(/Заметка о выплате: Наличными в офисе/)).toBeInTheDocument();
  });

  it('takes a request into work', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('В работу'));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/1/processing', undefined));
  });

  it('PAID needs a payout note, warns about bank details and sends the trimmed note', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Выплачено' }));
    const form = screen.getByTestId('paid-form-2');
    const send = within(form).getByRole('button', { name: /Подтвердить выплату/ });

    expect(within(form).getByText(/Не указывайте полный номер карты, счёта/)).toBeInTheDocument();
    expect(send).toBeDisabled();
    fireEvent.change(within(form).getByLabelText('Заметка о выплате'), { target: { value: '   ' } });
    expect(send).toBeDisabled();
    expect(apiPost).not.toHaveBeenCalled();

    fireEvent.change(within(form).getByLabelText('Заметка о выплате'), { target: { value: ' Наличными, чек 77 ' } });
    fireEvent.click(send);
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/2/paid', { payoutNote: 'Наличными, чек 77' }));
  });

  it('rejecting a PENDING request needs a reason and the no-payout confirmation; sends the status seen', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('Отклонить'));
    const form = screen.getByTestId('reject-form-1');
    const send = within(form).getByText('Отклонить и вернуть сумму');

    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: ' Неверные реквизиты ' } });
    expect(send).toBeDisabled();   // reason alone is not enough
    fireEvent.click(within(form).getByRole('checkbox'));
    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: '   ' } });
    expect(send).toBeDisabled();   // confirmation alone is not enough
    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: ' Неверные реквизиты ' } });
    fireEvent.click(send);

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/1/reject',
      { reason: 'Неверные реквизиты', payoutNotMade: true, expectedStatus: 'PENDING' }));
  });

  it('a PROCESSING request can be rejected too, with expectedStatus PROCESSING', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Отклонить' }));
    const form = screen.getByTestId('reject-form-2');
    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: 'Выплата невозможна' } });
    fireEvent.click(within(form).getByRole('checkbox'));
    fireEvent.click(within(form).getByText('Отклонить и вернуть сумму'));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/2/reject',
      { reason: 'Выплата невозможна', payoutNotMade: true, expectedStatus: 'PROCESSING' }));
  });

  it('switching between the forms never carries a confirmation over', async () => {
    render(<AdminWithdrawalsPage />);
    const row = await screen.findByTestId('withdrawal-2');
    fireEvent.click(within(row).getByRole('button', { name: 'Отклонить' }));
    fireEvent.click(within(screen.getByTestId('reject-form-2')).getByRole('checkbox'));
    fireEvent.click(within(row).getByRole('button', { name: 'Выплачено' }));
    fireEvent.click(within(row).getByRole('button', { name: 'Отклонить' }));

    expect(within(screen.getByTestId('reject-form-2')).getByRole('checkbox')).not.toBeChecked();
  });

  it('shows the server refusal (stale status, already handled) and reloads the list', async () => {
    apiPost.mockRejectedValueOnce({ response: { data: { message: 'Статус заявки на вывод #1 изменился: ожидался PENDING, сейчас PROCESSING. Обновите страницу.' } } });
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('Отклонить'));
    const form = screen.getByTestId('reject-form-1');
    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: 'x' } });
    fireEvent.click(within(form).getByRole('checkbox'));
    const loadsBefore = apiGet.mock.calls.length;
    fireEvent.click(within(form).getByText('Отклонить и вернуть сумму'));

    expect(await screen.findByText(/изменился: ожидался PENDING/)).toBeInTheDocument();
    await waitFor(() => expect(apiGet.mock.calls.length).toBeGreaterThan(loadsBefore));
  });
});
