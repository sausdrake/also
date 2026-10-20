import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { AdminWithdrawalsPage } from './AdminWithdrawalsPage';
import api from '../../api/client';

/**
 * FE-WD-01 adversarial: the admin payout queue. A queue that failed to load must never read
 * "Заявок нет." (an admin would conclude nothing is waiting to be paid); actions come from the
 * domain transition table; one state change is sent at a time; refusals keep their reasons.
 * No real money moves: the API is mocked.
 */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

const base = { userId: 5, username: 'seller5', email: 's5@test.com', amount: 3000, createdAt: '2026-10-15T10:00:00Z',
  processingStartedAt: null, processingStartedBy: null, processedAt: null, processedBy: null, rejectionReason: null, payoutNote: null };

beforeEach(() => {
  apiGet.mockReset();
  apiPost.mockReset();
});

describe('FE-WD-01: admin queue load failures are not an empty queue', () => {
  it.each([
    ['403', httpError(403, { error: 'Forbidden', message: 'Access is denied' }), 'Недостаточно прав для этого действия.'],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером'],
  ])('%s -> error with retry, never "Заявок нет."', async (_n, error, text) => {
    apiGet.mockRejectedValueOnce(error).mockResolvedValue({ data: [{ ...base, id: 1, status: 'PENDING' }] });
    render(<AdminWithdrawalsPage />);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(screen.queryByText('Заявок нет.')).not.toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByTestId('withdrawal-1')).toBeInTheDocument();
  });

  it.each([['object', { content: [] }], ['null', null], ['string', '<html/>']])('a malformed (%s) body is an error', async (_n, data) => {
    apiGet.mockResolvedValue({ data });
    render(<AdminWithdrawalsPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Не удалось загрузить заявки на вывод');
    expect(screen.queryByText('Заявок нет.')).not.toBeInTheDocument();
  });

  it('a successful [] is the empty queue', async () => {
    apiGet.mockResolvedValue({ data: [] });
    render(<AdminWithdrawalsPage />);
    expect(await screen.findByText('Заявок нет.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});

describe('FE-WD-01: actions and refusals', () => {
  it('an unknown status shows its raw value and offers no action', async () => {
    apiGet.mockResolvedValue({ data: [{ ...base, id: 7, status: 'ON_HOLD' }] });
    render(<AdminWithdrawalsPage />);
    const row = await screen.findByTestId('withdrawal-7');
    expect(within(row).getByText('ON_HOLD')).toBeInTheDocument();
    expect(within(row).queryAllByRole('button')).toHaveLength(0);
  });

  it('a double click on "В работу" sends one POST', async () => {
    apiGet.mockResolvedValue({ data: [{ ...base, id: 1, status: 'PENDING' }] });
    apiPost.mockImplementation(() => new Promise(() => {}));
    render(<AdminWithdrawalsPage />);
    const take = within(await screen.findByTestId('withdrawal-1')).getByText('В работу');
    fireEvent.click(take);
    fireEvent.click(take);
    fireEvent.click(take);
    expect(apiPost).toHaveBeenCalledTimes(1);
  });

  it('a double click on the payout confirmation sends one POST', async () => {
    apiGet.mockResolvedValue({ data: [{ ...base, id: 2, status: 'PROCESSING' }] });
    apiPost.mockImplementation(() => new Promise(() => {}));
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Выплачено' }));
    const form = screen.getByTestId('paid-form-2');
    fireEvent.change(within(form).getByLabelText('Заметка о выплате'), { target: { value: 'Наличными' } });
    const send = within(form).getByRole('button', { name: /Подтвердить выплату/ });
    fireEvent.click(send);
    fireEvent.click(send);
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/2/paid', { payoutNote: 'Наличными' });
  });

  it('a validation refusal keeps the field message (details), not a generic text', async () => {
    apiGet.mockResolvedValue({ data: [{ ...base, id: 2, status: 'PROCESSING' }] });
    apiPost.mockRejectedValue(httpError(400, { error: 'Ошибка валидации', details: { payoutNote: 'Заметка о выплате не может быть длиннее 1000 символов' } }));
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Выплачено' }));
    const form = screen.getByTestId('paid-form-2');
    fireEvent.change(within(form).getByLabelText('Заметка о выплате'), { target: { value: 'x' } });
    fireEvent.click(within(form).getByRole('button', { name: /Подтвердить выплату/ }));
    expect(await screen.findByText('Заметка о выплате не может быть длиннее 1000 символов')).toBeInTheDocument();
  });

  it('reject sends the status the admin saw (parsed), so a stale screen is refused by the server', async () => {
    apiGet.mockResolvedValue({ data: [{ ...base, id: 2, status: 'PROCESSING' }] });
    apiPost.mockResolvedValue({ data: {} });
    render(<AdminWithdrawalsPage />);
    fireEvent.click(within(await screen.findByTestId('withdrawal-2')).getByRole('button', { name: 'Отклонить' }));
    const form = screen.getByTestId('reject-form-2');
    fireEvent.change(within(form).getByLabelText('Причина отклонения'), { target: { value: 'Нет реквизитов' } });
    fireEvent.click(within(form).getByRole('checkbox'));
    fireEvent.click(within(form).getByText('Отклонить и вернуть сумму'));
    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/withdrawals/2/reject',
      { reason: 'Нет реквизитов', payoutNotMade: true, expectedStatus: 'PROCESSING' }));
  });
});
