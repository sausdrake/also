import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AdminWithdrawalsPage } from './AdminWithdrawalsPage';
import api from '../../api/client';

/** F6: admin processing of manual payout requests. */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const base = { userId: 5, username: 'seller5', email: 's5@test.com', amount: 3000, createdAt: '2026-10-15T10:00:00Z',
  processingStartedAt: null, processingStartedBy: null, processedAt: null, processedBy: null, rejectionReason: null };
const pending = { ...base, id: 1, status: 'PENDING' };
const processing = { ...base, id: 2, status: 'PROCESSING', processingStartedAt: '2026-10-15T11:00:00Z', processingStartedBy: 9 };
const paid = { ...processing, id: 3, status: 'PAID', processedAt: '2026-10-15T12:00:00Z', processedBy: 9 };

beforeEach(() => {
  apiGet.mockReset().mockResolvedValue({ data: [pending, processing, paid] });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AdminWithdrawalsPage (F6)', () => {
  it('loads the pending queue first and offers only the actions each status allows', async () => {
    render(<AdminWithdrawalsPage />);
    const p = await screen.findByTestId('withdrawal-1');

    expect(apiGet).toHaveBeenCalledWith('/admin/withdrawals', { params: { status: 'PENDING' } });
    expect(within(p).getByText('В работу')).toBeInTheDocument();
    expect(within(p).getByText('Отклонить')).toBeInTheDocument();
    expect(within(p).queryByText('Выплачено')).not.toBeInTheDocument();

    const pr = screen.getByTestId('withdrawal-2');
    expect(within(pr).getByRole('button', { name: 'Выплачено' })).toBeInTheDocument();
    expect(within(pr).queryByText('Отклонить')).not.toBeInTheDocument();

    const done = screen.getByTestId('withdrawal-3');
    expect(within(done).queryAllByRole('button')).toHaveLength(0);
  });

  it('takes a request into work', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('В работу'));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/1/processing', undefined));
  });

  it('marks paid only after an explicit confirmation', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true);
    render(<AdminWithdrawalsPage />);
    const button = within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Выплачено' });

    fireEvent.click(button);
    expect(apiPost).not.toHaveBeenCalled();
    fireEvent.click(button);
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/2/paid', undefined));
    expect(confirm).toHaveBeenCalledTimes(2);
  });

  it('rejects only with a reason, which is sent trimmed', async () => {
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('Отклонить'));
    const send = screen.getByText('Отклонить и вернуть сумму');
    expect(send).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Причина отклонения'), { target: { value: '   ' } });
    expect(send).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Причина отклонения'), { target: { value: ' Неверные реквизиты ' } });
    fireEvent.click(send);

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/1/reject', { reason: 'Неверные реквизиты' }));
  });

  it('shows the server refusal (e.g. an already handled request)', async () => {
    apiPost.mockRejectedValueOnce({ response: { data: { message: 'Заявку на вывод #1 в статусе PAID нельзя взять в работу' } } });
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-1')).getByText('В работу'));
    expect(await screen.findByText(/нельзя взять в работу/)).toBeInTheDocument();
  });
});
