import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * FE-AUCTION-01: the deposit to take part in a lot is a fixed amount the admin enters per lot
 * (AuctionCreateRequest.requiredDepositAmount - "НЕ процент от стартовой цены"). BidService refuses
 * every bid and Buy Now on a lot without it, so the form requires it and never invents a value.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [] } });
    if (url === '/admin/vehicles/approved') return Promise.resolve({ data: [{ id: 9, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001' }] });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: {} });
  axiosPost.mockReset().mockResolvedValue({ data: { publicUrl: 'https://cdn.example/main.jpg' } });
});

async function openCreateForm() {
  const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
  const { container } = render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('+ Создать аукцион'));
  return container;
}

async function fillValidForm(container: HTMLElement, deposit: string) {
  fireEvent.change(container.querySelector('select')!, { target: { value: '9' } });
  fireEvent.change(screen.getByLabelText('Стартовая цена *'), { target: { value: '1000000' } });
  fireEvent.change(screen.getByLabelText('Шаг ставки *'), { target: { value: '10000' } });
  if (deposit !== '') fireEvent.change(screen.getByLabelText('Депозит для участия *'), { target: { value: deposit } });
  const [start, end] = Array.from(container.querySelectorAll('input[type="datetime-local"]'));
  fireEvent.change(start, { target: { value: '2026-11-01T10:00' } });
  fireEvent.change(end, { target: { value: '2026-11-08T10:00' } });
  const file = new File(['x'], 'main.jpg', { type: 'image/jpeg' });
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  await screen.findByText('Гл.');
}

const createCalls = () => apiPost.mock.calls.filter(c => c[0] === '/admin/auctions');

describe('FE-AUCTION-01: the admin sets the deposit for each lot', () => {
  it('sends the entered deposit as requiredDepositAmount', async () => {
    const container = await openCreateForm();
    await fillValidForm(container, '50000');
    fireEvent.click(screen.getByText('Создать'));

    await waitFor(() => expect(createCalls()).toHaveLength(1));
    expect(createCalls()[0][1]).toMatchObject({ vehicleId: 9, startingPrice: 1000000, minBidStep: 10000, requiredDepositAmount: 50000 });
  });

  it('the deposit is not pre-filled or derived from the price', async () => {
    await openCreateForm();
    expect(screen.getByLabelText('Депозит для участия *')).toHaveValue(null);
    fireEvent.change(screen.getByLabelText('Стартовая цена *'), { target: { value: '1000000' } });
    expect(screen.getByLabelText('Депозит для участия *')).toHaveValue(null);
  });

  it('the backend validation message is shown when the create is refused', async () => {
    apiPost.mockRejectedValue(Object.assign(new Error('400'), { isAxiosError: true, response: { status: 400,
      data: { error: 'Ошибка валидации', details: { requiredDepositAmount: 'Требуемый депозит должен быть больше 0' } } } }));
    const container = await openCreateForm();
    await fillValidForm(container, '50000');
    fireEvent.click(screen.getByText('Создать'));
    expect(await screen.findByText('Требуемый депозит должен быть больше 0')).toBeInTheDocument();
  });
});

describe('FE-AUCTION-01 adversarial: no lot without a positive deposit', () => {
  for (const [name, value] of [['empty', ''], ['zero', '0'], ['negative', '-5'], ['too large', '1000000000'], ['scientific junk', '1e']] as const) {
    it(`${name} deposit blocks the create and sends nothing`, async () => {
      const container = await openCreateForm();
      await fillValidForm(container, value);
      fireEvent.click(screen.getByText('Создать'));
      expect(await screen.findByText(value === '1000000000' ? 'Слишком большая сумма' : 'Укажите депозит для участия (больше 0)')).toBeInTheDocument();
      expect(createCalls()).toHaveLength(0);
    });
  }
});
