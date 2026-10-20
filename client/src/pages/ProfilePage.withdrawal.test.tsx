import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/**
 * FE-WD-01: the owner's withdrawal form and list. No real money moves here - POST /withdrawals is
 * mocked; the point is what the form sends and what the user sees.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn(), patch: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

function mockApi({ available = 1000, withdrawals = [] as unknown }: { available?: number | null; withdrawals?: unknown } = {}) {
  let wi = 0;
  const list = Array.isArray(withdrawals) && withdrawals.length && (withdrawals[0] instanceof Error || withdrawals[0] === 'SEQ')
    ? withdrawals : null;
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/deposits/my') return Promise.resolve({ data: { balance: available ?? 0, blockedAmount: 0, availableBalance: available } });
    if (url === '/withdrawals/my') {
      const a = list ? list[Math.min(1 + wi++, list.length - 1)] : withdrawals;
      return a instanceof Error ? Promise.reject(a) : Promise.resolve({ data: a });
    }
    return Promise.resolve({ data: [] });
  });
}

function renderDeposit() {
  render(<RouterProvider router={createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile'] })} />);
}

const amountInput = () => screen.findByPlaceholderText('Сумма');
const withdrawButton = () => screen.getByRole('button', { name: /Вывести|Отправка…/ });

beforeEach(() => {
  apiPost.mockReset();
});

describe('FE-WD-01: withdrawal form', () => {
  it('a double click sends one POST; the button shows the request in flight', async () => {
    mockApi();
    let release: () => void = () => {};
    apiPost.mockImplementation(() => new Promise(r => { release = () => r({ data: {} }); }));
    renderDeposit();
    fireEvent.change(await amountInput(), { target: { value: '250' } });
    fireEvent.click(withdrawButton());
    fireEvent.click(withdrawButton());
    fireEvent.click(withdrawButton());
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(withdrawButton()).toHaveTextContent('Отправка…');
    expect(withdrawButton()).toBeDisabled();
    release();
    expect(await screen.findByText(/Заявка на вывод создана/)).toBeInTheDocument();
    expect(withdrawButton()).toBeEnabled();
  });

  it('sends a decimal amount as a number; the idempotency key is reused on retry after a failure', async () => {
    mockApi();
    apiPost.mockRejectedValueOnce(Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} })).mockResolvedValueOnce({ data: {} });
    renderDeposit();
    fireEvent.change(await amountInput(), { target: { value: '10.5' } });
    fireEvent.click(withdrawButton());
    expect(await screen.findByText(/Нет связи с сервером/)).toBeInTheDocument();
    fireEvent.click(withdrawButton());
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(2));
    expect(apiPost.mock.calls[0][1].amount).toBe(10.5);
    expect(apiPost.mock.calls[1][1].idempotencyKey).toBe(apiPost.mock.calls[0][1].idempotencyKey);
  });

  it.each([
    ['100.555', 'Не больше двух знаков после запятой'],
    ['0', 'Сумма должна быть больше 0'],
    ['1000.01', /^Максимум: 1\s000 сом$/],
  ])('%s is refused on the client and nothing is sent', async (raw, message) => {
    mockApi();
    renderDeposit();
    fireEvent.change(await amountInput(), { target: { value: raw } });
    fireEvent.click(withdrawButton());
    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('nothing available -> the button is disabled and says why', async () => {
    mockApi({ available: 0 });
    renderDeposit();
    expect(await screen.findByText('Нет доступных средств для вывода.')).toBeInTheDocument();
    expect(withdrawButton()).toBeDisabled();
  });

  it('a backend validation refusal shows the field message', async () => {
    mockApi();
    apiPost.mockRejectedValue(httpError(400, { error: 'Ошибка валидации', details: { amount: 'Не больше двух знаков после запятой' } }));
    renderDeposit();
    fireEvent.change(await amountInput(), { target: { value: '5' } });
    fireEvent.click(withdrawButton());
    expect(await screen.findByText('Не больше двух знаков после запятой')).toBeInTheDocument();
  });
});

describe('FE-WD-01 adversarial: own requests list', () => {
  it('statuses use the owner wording; an unknown status shows its raw value; bad rows are dropped', async () => {
    mockApi({ withdrawals: [
      { id: 1, amount: 300, status: 'REJECTED', createdAt: '2026-10-14T10:00:00Z', processedAt: null, rejectionReason: 'Неверные реквизиты' },
      { id: 2, amount: 200, status: 'PENDING', createdAt: '2026-10-15T10:00:00Z', processedAt: null, rejectionReason: null },
      { id: 3, amount: 100, status: 'ESCALATED', createdAt: '2026-10-16T10:00:00Z', processedAt: null, rejectionReason: null },
      null, { id: 'x', amount: 5 }, { id: 4, amount: 'много' },
    ] });
    renderDeposit();
    const list = await screen.findByTestId('my-withdrawals');
    expect(within(list).getByText('Отклонено, сумма возвращена')).toBeInTheDocument();
    expect(within(list).getByText('Ожидает обработки')).toBeInTheDocument();
    expect(within(list).getByText('ESCALATED')).toBeInTheDocument();
    expect(within(list).getAllByRole('listitem')).toHaveLength(3);
  });

  it.each([
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['malformed', { content: [] }, 'Не удалось загрузить заявки на вывод'],
  ])('a %s list is an error with a retry, not a silently missing list', async (_n, answer, text) => {
    mockApi({ withdrawals: ['SEQ', answer, [{ id: 9, amount: 50, status: 'PAID', createdAt: '2026-10-16T10:00:00Z', processedAt: null, rejectionReason: null }]] });
    renderDeposit();
    const alert = await screen.findByText(text);
    fireEvent.click(within(alert.closest('[role=alert]') as HTMLElement).getByRole('button', { name: 'Повторить' }));
    const list = await screen.findByTestId('my-withdrawals');
    expect(within(list).getByText('Выплачено')).toBeInTheDocument();
  });
});
