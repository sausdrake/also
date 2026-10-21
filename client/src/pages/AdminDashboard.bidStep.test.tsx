import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * AUCTION-01 (BD-02): the create form has no bid-step input. It shows the server's own derivation for the
 * typed starting price (Стартовая цена / Минимальный шаг / Минимальная первая ставка) and validates prices
 * as money the backend can store.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;
const http400 = () => Object.assign(new Error('400'), { isAxiosError: true, response: { status: 400, data: {} } });

let preview: (price: string) => Promise<unknown>;

beforeEach(() => {
  preview = (price) => price === '1234567'
    ? Promise.resolve({ data: { startingPrice: 1234567, minimumBidStep: 12345.67, minimumFirstBid: 1246912.67 } })
    : price === '123.45'
      ? Promise.resolve({ data: { startingPrice: 123.45, minimumBidStep: 1.235, minimumFirstBid: 124.69 } })
      : Promise.reject(http400());
  apiGet.mockReset().mockImplementation((url: string, config?: { params?: { startingPrice?: string } }) => {
    if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [] } });
    if (url === '/admin/vehicles/approved') return Promise.resolve({ data: [{ id: 9, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001' }] });
    if (url === '/admin/settings') return Promise.resolve({ data: { auction_participation_deposit_amount: '15000.00' } });
    if (url === '/admin/auctions/bid-step') return preview(String(config?.params?.startingPrice));
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: {} });
  axiosPost.mockReset().mockResolvedValue({ data: { publicUrl: 'https://cdn.example/main.jpg' } });
});

async function openCreateForm() {
  const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
  const { container } = render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('+ Создать аукцион'));
  await waitFor(() => expect(screen.getByTestId('participation-deposit')).toHaveTextContent(/15\s000 сом/));
  return container;
}

const setPrice = (value: string) => fireEvent.change(screen.getByLabelText('Стартовая цена *'), { target: { value } });

async function fillRest(container: HTMLElement) {
  fireEvent.change(container.querySelector('select')!, { target: { value: '9' } });
  const [start, end] = Array.from(container.querySelectorAll('input[type="datetime-local"]'));
  fireEvent.change(start, { target: { value: '2026-11-01T10:00' } });
  fireEvent.change(end, { target: { value: '2026-11-08T10:00' } });
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(['x'], 'main.jpg', { type: 'image/jpeg' })] } });
  await screen.findByText('Гл.');
}

const createCalls = () => apiPost.mock.calls.filter(c => c[0] === '/admin/auctions');
const stepCalls = () => apiGet.mock.calls.filter(c => c[0] === '/admin/auctions/bid-step');

describe('AUCTION-01: bid step on the create form', () => {
  it('there is no bid-step input', async () => {
    await openCreateForm();
    expect(screen.queryByLabelText('Шаг ставки *')).toBeNull();
  });

  it('shows the server derivation for the BD-02 example (3-decimal step)', async () => {
    await openCreateForm();
    setPrice('1234567');
    const box = screen.getByTestId('bid-step-preview');
    await waitFor(() => expect(box).toHaveTextContent(/Минимальный шаг: 12\s345,67 сом/));
    expect(box).toHaveTextContent(/Стартовая цена: 1\s234\s567 сом/);
    expect(box).toHaveTextContent(/Минимальная первая ставка: 1\s246\s912,67 сом/);
  });

  it('a step with a third decimal is shown with it', async () => {
    await openCreateForm();
    setPrice('123.45');
    await waitFor(() => expect(screen.getByTestId('bid-step-preview')).toHaveTextContent(/Минимальный шаг: 1,235 сом/));
    expect(screen.getByTestId('bid-step-preview')).toHaveTextContent(/Минимальная первая ставка: 124,69 сом/);
  });

  it('an invalid price asks the server nothing', async () => {
    await openCreateForm();
    for (const v of ['123.456', '0', '-5', '100000000', 'abc']) setPrice(v);
    await new Promise(r => setTimeout(r, 400));
    expect(stepCalls()).toHaveLength(0);
    expect(screen.getByTestId('bid-step-preview')).toHaveTextContent(/Минимальный шаг: —/);
  });

  it('the create request carries the starting price and no step', async () => {
    const container = await openCreateForm();
    setPrice('123.45');
    await waitFor(() => expect(screen.getByTestId('bid-step-preview')).toHaveTextContent(/1,235/));
    await fillRest(container);
    fireEvent.click(screen.getByText('Создать'));
    await waitFor(() => expect(createCalls()).toHaveLength(1));
    expect(createCalls()[0][1]).toMatchObject({ startingPrice: 123.45 });
    expect(createCalls()[0][1]).not.toHaveProperty('minBidStep');
  });

  it.each([
    ['startingPrice', '123.456', 'Не больше 2 знаков после запятой'],
    ['startingPrice', '100000000', 'Максимум: 99 999 999.99 сом'],
    ['startingPrice', '0.04', 'Слишком маленькая цена: шаг ставки округляется до 0'],
    ['reservePrice', '1.001', 'Не больше 2 знаков после запятой'],
    ['buyNowPrice', '100000000', 'Максимум: 99 999 999.99 сом'],
  ])('%s %s is refused on the page (%s) and nothing is sent', async (field, value, error) => {
    const container = await openCreateForm();
    setPrice(field === 'startingPrice' ? value : '123.45');
    if (field !== 'startingPrice') {
      const label = field === 'reservePrice' ? 'Резервная цена (опц.)' : 'Buy Now (опц.)';
      fireEvent.change(screen.getByLabelText(label), { target: { value } });
    }
    if (value === '0.04') await waitFor(() => expect(stepCalls().length).toBeGreaterThan(0));
    await fillRest(container);
    await new Promise(r => setTimeout(r, 300));
    fireEvent.click(screen.getByText('Создать'));
    expect(await screen.findByText(error)).toBeInTheDocument();
    expect(createCalls()).toHaveLength(0);
  });
});
