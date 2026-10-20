import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AuctionPage } from './AuctionPage';
import api from '../api/client';
import { useAuthStore } from '../store/authStore';

/**
 * FE-SUB-01: "Следить за лотом" on the lot page. It used to call /api/v1/subscriptions (404),
 * swallow every error and keep whatever it believed. Now the state is shown only once the backend
 * confirms it, a failure is explained, and a click in flight cannot send a second mutation.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));
vi.mock('../hooks/useWebSocket', () => ({ useWebSocket: () => ({ isConnected: false, lastMessage: null }) }));

const axiosGet = axios.get as unknown as ReturnType<typeof vi.fn>;
const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const apiDelete = api.delete as unknown as ReturnType<typeof vi.fn>;

const SUBS = { baseURL: 'http://api.test/api' };
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });
const auction = {
  id: 1, vehicleId: 9, vehicleInfo: 'BMW X5 2020', status: 'ACTIVE', startingPrice: 1000000, currentPrice: 1000000,
  minBidStep: 10000, buyNowPrice: null, startTime: '2026-10-15T00:00:00Z', endTime: '2099-12-31T00:00:00Z', totalBids: 0,
  requiredDepositAmount: 50000,
};

const SUBSCRIBE = '⭐ Следить за лотом';
const SUBSCRIBED = '⭐ Вы подписаны';

function mockStatus(answer: unknown) {
  apiGet.mockImplementation((url: string, config?: unknown) => {
    if (url === '/subscriptions/1/status') {
      expect(config).toEqual(SUBS);
      return answer instanceof Error ? Promise.reject(answer) : Promise.resolve({ data: answer });
    }
    return Promise.resolve({ data: {} });
  });
}

async function renderLot() {
  const router = createMemoryRouter([{ path: '/auction/:id', element: <AuctionPage /> }], { initialEntries: ['/auction/1'] });
  render(<RouterProvider router={router} />);
  await screen.findByText('Депозит для участия');
}

const button = () => screen.getByRole('button', { name: new RegExp(`${SUBSCRIBE}|${SUBSCRIBED}`) });

beforeEach(() => {
  axiosGet.mockReset().mockImplementation((url: unknown) => String(url).includes('/api/public/auctions/1')
    ? Promise.resolve({ data: auction }) : Promise.resolve({ data: {} }));
  apiGet.mockReset();
  apiPost.mockReset();
  apiDelete.mockReset();
  useAuthStore.setState({ isAuthenticated: true, accessToken: 'test-token', user: { id: 5, username: 'buyer', email: 'b@test.com', roles: ['ROLE_USER'] } });
});

afterEach(() => {
  useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null });
});

describe('FE-SUB-01: lot subscription button', () => {
  it('confirmed subscribed -> "Вы подписаны"', async () => {
    mockStatus({ subscribed: true });
    await renderLot();
    await waitFor(() => expect(button()).toHaveTextContent(SUBSCRIBED));
    expect(button()).toHaveAttribute('aria-pressed', 'true');
  });

  it('confirmed not subscribed -> subscribe; the state flips only after the POST succeeds', async () => {
    mockStatus({ subscribed: false });
    let release: () => void = () => {};
    apiPost.mockImplementation(() => new Promise(r => { release = () => r({ data: '' }); }));
    await renderLot();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/subscriptions/1/status', SUBS));
    fireEvent.click(button());
    expect(apiPost).toHaveBeenCalledWith('/subscriptions/1', undefined, SUBS);
    expect(button()).toHaveTextContent(SUBSCRIBE);
    release();
    await waitFor(() => expect(button()).toHaveTextContent(SUBSCRIBED));
  });

  it('unsubscribe flips back only after the DELETE succeeds', async () => {
    mockStatus({ subscribed: true });
    apiDelete.mockResolvedValue({ data: '' });
    await renderLot();
    await waitFor(() => expect(button()).toHaveTextContent(SUBSCRIBED));
    fireEvent.click(button());
    await waitFor(() => expect(button()).toHaveTextContent(SUBSCRIBE));
    expect(apiDelete).toHaveBeenCalledWith('/subscriptions/1', SUBS);
  });

  it('an anonymous visitor gets no button and no subscription request', async () => {
    useAuthStore.setState({ isAuthenticated: false, accessToken: null, user: null });
    mockStatus({ subscribed: true });
    await renderLot();
    expect(screen.queryByRole('button', { name: new RegExp(`${SUBSCRIBE}|${SUBSCRIBED}`) })).not.toBeInTheDocument();
    expect(apiGet).not.toHaveBeenCalledWith('/subscriptions/1/status', expect.anything());
  });
});

describe('FE-SUB-01 adversarial: never a false "Вы подписаны"', () => {
  it.each([
    ['subscribed="true"', { subscribed: 'true' }],
    ['subscribed=1', { subscribed: 1 }],
    ['empty body', {}],
    ['401', httpError(401, { error: 'Unauthorized' })],
    ['404', httpError(404, { error: 'Not Found' })],
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' })],
    ['network', Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} })],
  ])('status %s -> not shown as subscribed, the failure is noted', async (_name, answer) => {
    mockStatus(answer);
    await renderLot();
    expect(await screen.findByText('Не удалось проверить подписку на этот лот.')).toBeInTheDocument();
    expect(button()).toHaveTextContent(SUBSCRIBE);
    expect(button()).toHaveAttribute('aria-pressed', 'false');
  });

  it('a failed subscribe keeps the state and shows the backend reason', async () => {
    mockStatus({ subscribed: false });
    apiPost.mockRejectedValue(httpError(409, { message: 'Подписка сейчас недоступна' }));
    await renderLot();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/subscriptions/1/status', SUBS));
    fireEvent.click(button());
    expect(await screen.findByText('Подписка сейчас недоступна')).toBeInTheDocument();
    expect(button()).toHaveTextContent(SUBSCRIBE);
  });

  it('a failed unsubscribe keeps "Вы подписаны" and explains', async () => {
    mockStatus({ subscribed: true });
    apiDelete.mockRejectedValue(Object.assign(new Error('Network Error'), { isAxiosError: true, request: {} }));
    await renderLot();
    await waitFor(() => expect(button()).toHaveTextContent(SUBSCRIBED));
    fireEvent.click(button());
    expect(await screen.findByText(/Нет связи с сервером/)).toBeInTheDocument();
    expect(button()).toHaveTextContent(SUBSCRIBED);
  });

  it('a double click sends one POST', async () => {
    mockStatus({ subscribed: false });
    apiPost.mockImplementation(() => new Promise(() => {}));
    await renderLot();
    await waitFor(() => expect(apiGet).toHaveBeenCalledWith('/subscriptions/1/status', SUBS));
    const b = button();
    fireEvent.click(b);
    fireEvent.click(b);
    fireEvent.click(b);
    expect(apiPost).toHaveBeenCalledTimes(1);
  });
});
