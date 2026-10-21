import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { ProfilePage } from './ProfilePage';
import api from '../api/client';

/** Scenario 1 step 4: each diagnostic request offers "Просмотр" (details, incl. rejection reason). */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;

const requests = [
  { id: 1, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001', status: 'PENDING',
    depositFrozen: 10000, createdAt: '2026-10-14T10:00:00Z', updatedAt: '2026-10-14T10:00:00Z' },
  { id: 2, brand: 'Toyota', model: 'Camry', year: 2021, vin: 'JTNB11HK103456789', status: 'REJECTED',
    depositFrozen: 10000, diagnosticName: 'diag1', rejectedReason: 'Не хватает фото двигателя',
    createdAt: '2026-10-10T10:00:00Z', updatedAt: '2026-10-12T10:00:00Z' },
];

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url.startsWith('/diagnostic/requests/my')) return Promise.resolve({ data: { content: requests } });
    return Promise.resolve({ data: {} });
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function renderRequests() {
  const router = createMemoryRouter([{ path: '/profile/*', element: <ProfilePage /> }], { initialEntries: ['/profile/requests'] });
  render(<RouterProvider router={router} />);
  return screen.findAllByText('Просмотр');
}

describe('ProfilePage requests - "Просмотр" (scenario 1.4)', () => {
  it('every request has "Просмотр"; only the pending one can be withdrawn', async () => {
    const view = await renderRequests();
    expect(view).toHaveLength(2);
    expect(screen.getAllByText('Отозвать')).toHaveLength(1);
  });

  it('shows the details of the chosen request, including the rejection reason', async () => {
    const view = await renderRequests();
    fireEvent.click(view[1]);

    const details = screen.getByTestId('request-details-2');
    expect(within(details).getByText('Не хватает фото двигателя')).toBeInTheDocument();
    expect(within(details).getByText('diag1')).toBeInTheDocument();
    expect(screen.queryByTestId('request-details-1')).not.toBeInTheDocument();

    fireEvent.click(screen.getByText('Скрыть'));
    expect(screen.queryByTestId('request-details-2')).not.toBeInTheDocument();
  });

  it('a request without a diagnostician or rejection says so', async () => {
    const view = await renderRequests();
    fireEvent.click(view[0]);

    const details = screen.getByTestId('request-details-1');
    expect(within(details).getByText('ещё не назначен')).toBeInTheDocument();
    expect(within(details).queryByText('Причина отклонения')).not.toBeInTheDocument();
  });
});

describe('FE-INFLIGHT-01 (BL-21): "Создать" sends one request at a time', () => {
  const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

  async function fillForm() {
    await renderRequests();
    fireEvent.click(screen.getByText('+ Подать заявку'));
    fireEvent.change(screen.getByPlaceholderText(/^Марка/), { target: { value: 'Toyota' } });
    fireEvent.change(screen.getByPlaceholderText(/^Модель/), { target: { value: 'Camry' } });
    fireEvent.change(screen.getByPlaceholderText(/^Год/), { target: { value: '2019' } });
    fireEvent.change(screen.getByPlaceholderText(/^VIN/), { target: { value: 'JTNB11HK0K3000001' } });
  }

  it('a double tap posts once, the button is disabled in flight, and "Заявка создана" stays', async () => {
    let resolve!: (v: unknown) => void;
    apiPost.mockReset().mockImplementation(() => new Promise(res => { resolve = res; }));
    await fillForm();
    const button = screen.getByRole('button', { name: 'Создать' });
    // two clicks handled before React re-renders the button (both reach the handler)
    act(() => { button.click(); button.click(); });
    expect(apiPost).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: 'Отправка…' })).toBeDisabled();
    resolve({ data: {} });
    expect(await screen.findByText('Заявка создана')).toBeInTheDocument();
  });

  it('after a refusal the button is usable again', async () => {
    apiPost.mockReset().mockRejectedValueOnce(Object.assign(new Error('HTTP 409'), {
      isAxiosError: true, response: { status: 409, data: { status: 409, message: 'Заявка по этому VIN уже существует' } },
    }));
    await fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));
    expect(await screen.findByText('Заявка по этому VIN уже существует')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Создать' })).toBeEnabled();
    fireEvent.click(screen.getByRole('button', { name: 'Создать' }));
    expect(apiPost).toHaveBeenCalledTimes(2);
  });
});
