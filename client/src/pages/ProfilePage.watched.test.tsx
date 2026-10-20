import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/**
 * FE-SUB-01: "Отслеживаемые". The list used to come from /api/v1/subscriptions (404) and every
 * error was swallowed into "Вы не следите ни за одним лотом". Now: the empty text only after a
 * successful [], an error with "Повторить" otherwise, a card disappears only after a confirmed
 * DELETE, and statuses use the domain labels.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiDelete = api.delete as unknown as ReturnType<typeof vi.fn>;

const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });
const SUBS = { baseURL: 'http://api.test/api' };

const lot = (id: number, extra: Record<string, unknown> = {}) => ({
  auctionId: id, vehicleInfo: `Авто ${id}`, currentPrice: 1000 * id, startingPrice: 500, status: 'ACTIVE',
  endTime: '2099-01-01T00:00:00Z', mainPhotoUrl: null, ...extra,
});

function mockList(...answers: Array<unknown>) {
  let i = 0;
  apiGet.mockImplementation((url: string, config?: { baseURL?: string }) => {
    if (url === '/subscriptions') {
      expect(config).toEqual(SUBS);
      const a = answers[Math.min(i++, answers.length - 1)];
      return a instanceof Error ? Promise.reject(a) : Promise.resolve({ data: a });
    }
    return Promise.resolve({ data: [] });
  });
}

function renderWatched() {
  const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile/watched'] });
  render(<RouterProvider router={router} />);
}

beforeEach(() => {
  apiGet.mockReset();
  apiDelete.mockReset();
});

describe('FE-SUB-01: watched lots', () => {
  it('shows the lots with domain status labels', async () => {
    mockList([lot(1), lot(2, { status: 'WAITING_FOR_PAYMENT' }), lot(3, { status: 'CREATED' })]);
    renderWatched();
    expect(await screen.findByText('Авто 1')).toBeInTheDocument();
    expect(screen.getByText('Идут торги')).toBeInTheDocument();
    expect(screen.getByText('Ожидание оплаты покупателем')).toBeInTheDocument();
    expect(screen.getByText('Ожидает начала')).toBeInTheDocument();
    expect(screen.queryByText('Активен')).not.toBeInTheDocument();
    expect(screen.queryByText('Создан')).not.toBeInTheDocument();
  });

  it('a successful [] shows the empty text', async () => {
    mockList([]);
    renderWatched();
    expect(await screen.findByText('Вы не следите ни за одним лотом')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('unsubscribe removes the card only after the DELETE succeeds', async () => {
    mockList([lot(1), lot(2)]);
    let release: () => void = () => {};
    apiDelete.mockImplementation(() => new Promise(r => { release = () => r({ data: '' }); }));
    renderWatched();
    fireEvent.click(await screen.findByLabelText('Отписаться от лота #1'));
    expect(screen.getByText('Авто 1')).toBeInTheDocument();
    expect(apiDelete).toHaveBeenCalledWith('/subscriptions/1', SUBS);
    release();
    await waitFor(() => expect(screen.queryByText('Авто 1')).not.toBeInTheDocument());
    expect(screen.getByText('Авто 2')).toBeInTheDocument();
  });
});

describe('FE-SUB-01 adversarial: ERROR != EMPTY STATE', () => {
  it.each([
    ['401', httpError(401, { error: 'Unauthorized' }), 'Требуется вход в систему.'],
    ['404', httpError(404, { status: 404, error: 'Not Found' }), 'Запрошенные данные не найдены.'],
    ['409', httpError(409, { message: 'Конфликт данных подписки' }), 'Конфликт данных подписки'],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }), 'Нет связи с сервером'],
    ['malformed (null)', null, 'Не удалось загрузить отслеживаемые лоты'],
    ['malformed (HTML)', '<html>Bad Gateway</html>', 'Не удалось загрузить отслеживаемые лоты'],
  ])('%s -> error with retry, never the empty text', async (_name, answer, text) => {
    mockList(answer);
    renderWatched();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(within(alert).getByRole('button', { name: 'Повторить' })).toBeInTheDocument();
    expect(screen.queryByText('Вы не следите ни за одним лотом')).not.toBeInTheDocument();
  });

  it('retry after a failed load shows the list', async () => {
    mockList(httpError(500, { message: 'Произошла непредвиденная ошибка' }), [lot(5)]);
    renderWatched();
    fireEvent.click(within(await screen.findByRole('alert')).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText('Авто 5')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('a failed unsubscribe keeps the card and says why', async () => {
    mockList([lot(1)]);
    apiDelete.mockRejectedValue(httpError(500, { message: 'Произошла непредвиденная ошибка' }));
    renderWatched();
    fireEvent.click(await screen.findByLabelText('Отписаться от лота #1'));
    expect(await screen.findByRole('alert')).toHaveTextContent('Произошла непредвиденная ошибка');
    expect(screen.getByText('Авто 1')).toBeInTheDocument();
  });

  it('a double click on ✕ sends one DELETE', async () => {
    mockList([lot(1)]);
    apiDelete.mockImplementation(() => new Promise(() => {}));
    renderWatched();
    const x = await screen.findByLabelText('Отписаться от лота #1');
    fireEvent.click(x);
    fireEvent.click(x);
    await waitFor(() => expect(x).toBeDisabled());
    expect(apiDelete).toHaveBeenCalledTimes(1);
  });

  it('null / malformed elements and values never crash the list; sorting keeps missing values last', async () => {
    mockList([
      null, 'junk', { auctionId: 'x' },
      lot(1, { currentPrice: null, endTime: null, status: 'HACKED', vehicleInfo: null }),
      lot(2, { endTime: 'not-a-date', currentPrice: 'abc' }),
      lot(3, { endTime: '2099-01-02T00:00:00Z' }),
      lot(4, { endTime: '2099-01-01T00:00:00Z' }),
    ]);
    renderWatched();
    expect(await screen.findByText('Лот #1')).toBeInTheDocument();
    expect(screen.getByText('HACKED')).toBeInTheDocument();
    expect(screen.getAllByText('—')).toHaveLength(2);
    const order = () => screen.getAllByRole('button', { name: /^Отписаться от лота/ }).map(b => b.getAttribute('aria-label'));
    expect(order()).toEqual(['Отписаться от лота #4', 'Отписаться от лота #3', 'Отписаться от лота #1', 'Отписаться от лота #2']);
    fireEvent.change(screen.getByLabelText('Сортировка'), { target: { value: 'price_desc' } });
    expect(order()).toEqual(['Отписаться от лота #4', 'Отписаться от лота #3', 'Отписаться от лота #1', 'Отписаться от лота #2']);
    fireEvent.change(screen.getByLabelText('Сортировка'), { target: { value: 'price_asc' } });
    expect(order().slice(0, 2)).toEqual(['Отписаться от лота #3', 'Отписаться от лота #4']);
  });
});
