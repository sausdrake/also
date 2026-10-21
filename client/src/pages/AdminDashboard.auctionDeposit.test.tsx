import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import axios from 'axios';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * DEPOSIT-01 (BD-01): the participation deposit is ONE global admin setting
 * (auction_participation_deposit_amount) that the server snapshots into each new auction. The create
 * form shows it, never asks for it, and never sends a per-lot amount.
 */

vi.mock('axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const axiosPost = axios.post as unknown as ReturnType<typeof vi.fn>;

function mockApi(settings: Promise<unknown>) {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [] } });
    if (url === '/admin/vehicles/approved') return Promise.resolve({ data: [{ id: 9, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001' }] });
    if (url === '/admin/settings') return settings;
    if (url === '/admin/auctions/bid-step') return Promise.resolve({ data: { startingPrice: 1000000, minimumBidStep: 10000, minimumFirstBid: 1010000 } });
    return Promise.resolve({ data: [] });
  });
}

beforeEach(() => {
  mockApi(Promise.resolve({ data: { auction_participation_deposit_amount: '15000.00', diagnostic_fee_amount: '3000' } }));
  apiPost.mockReset().mockResolvedValue({ data: {} });
  axiosPost.mockReset().mockResolvedValue({ data: { publicUrl: 'https://cdn.example/main.jpg' } });
});

async function openCreateForm() {
  const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
  const { container } = render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('+ Создать аукцион'));
  return container;
}

async function fillValidForm(container: HTMLElement) {
  fireEvent.change(container.querySelector('select')!, { target: { value: '9' } });
  fireEvent.change(screen.getByLabelText('Стартовая цена *'), { target: { value: '1000000' } });
  const [start, end] = Array.from(container.querySelectorAll('input[type="datetime-local"]'));
  fireEvent.change(start, { target: { value: '2026-11-01T10:00' } });
  fireEvent.change(end, { target: { value: '2026-11-08T10:00' } });
  const file = new File(['x'], 'main.jpg', { type: 'image/jpeg' });
  fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [file] } });
  await screen.findByText('Гл.');
}

const createCalls = () => apiPost.mock.calls.filter(c => c[0] === '/admin/auctions');

describe('DEPOSIT-01: the create form uses the global participation deposit', () => {
  it('shows the configured amount and offers no deposit input', async () => {
    await openCreateForm();
    expect(await screen.findByTestId('participation-deposit')).toHaveTextContent(/Депозит для участия: 15\s000 сом/);
    expect(screen.queryByLabelText('Депозит для участия *')).toBeNull();
  });

  it('the create request carries no per-lot deposit (the server snapshots the setting)', async () => {
    const container = await openCreateForm();
    await waitFor(() => expect(screen.getByTestId('participation-deposit')).toHaveTextContent(/15\s000 сом/));
    await fillValidForm(container);
    fireEvent.click(screen.getByText('Создать'));

    await waitFor(() => expect(createCalls()).toHaveLength(1));
    expect(createCalls()[0][1]).toMatchObject({ vehicleId: 9, startingPrice: 1000000 });
    expect(createCalls()[0][1]).not.toHaveProperty('minBidStep');   // AUCTION-01: derived by the server
    expect(createCalls()[0][1]).not.toHaveProperty('requiredDepositAmount');
  });

  it('not set: a warning points to the settings and "Создать" is disabled - nothing is sent', async () => {
    mockApi(Promise.resolve({ data: { diagnostic_fee_amount: '3000' } }));
    const container = await openCreateForm();
    expect(await screen.findByText(/Депозит для участия не задан/)).toBeInTheDocument();
    await fillValidForm(container);
    expect(screen.getByRole('button', { name: 'Создать' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));
    expect(createCalls()).toHaveLength(0);
  });

  it('settings failing to load: an error with a retry, "Создать" disabled; the retry shows the amount', async () => {
    mockApi(Promise.reject(Object.assign(new Error('down'), { isAxiosError: true })));
    await openCreateForm();
    expect(await screen.findByText(/Не удалось загрузить депозит для участия/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать' })).toBeDisabled();

    mockApi(Promise.resolve({ data: { auction_participation_deposit_amount: '20000.00' } }));
    fireEvent.click(screen.getByText('Повторить'));
    await waitFor(() => expect(screen.getByTestId('participation-deposit')).toHaveTextContent(/Депозит для участия: 20\s000 сом/));
    expect(screen.getByRole('button', { name: 'Создать' })).toBeEnabled();
  });

  it('the backend refusal message is shown when the create is refused', async () => {
    apiPost.mockRejectedValue(Object.assign(new Error('409'), { isAxiosError: true, response: { status: 409,
      data: { message: 'Депозит для участия в аукционах не задан. Укажите его в разделе «Настройки».' } } }));
    const container = await openCreateForm();
    await waitFor(() => expect(screen.getByTestId('participation-deposit')).toHaveTextContent(/15\s000 сом/));
    await fillValidForm(container);
    fireEvent.click(screen.getByText('Создать'));
    expect(await screen.findByText(/Укажите его в разделе «Настройки»/)).toBeInTheDocument();
  });
});
