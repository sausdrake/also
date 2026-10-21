import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/** DEPOSIT-01 (BD-01): the global participation deposit in the admin settings, validated like the server does. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPut = api.put as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) =>
    Promise.resolve({ data: url === '/admin/settings' ? { diagnostic_fee_amount: '3000' } : [] }));
  apiPut.mockReset().mockResolvedValue({ data: {} });
});

async function depositField() {
  const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/settings'] });
  render(<RouterProvider router={router} />);
  return screen.findByLabelText(/Депозит для участия в аукционах/);
}

describe('DEPOSIT-01: participation deposit setting', () => {
  it('not set yet: shown as "Не задано" and explained', async () => {
    const field = await depositField();
    expect(field).toHaveValue(null);
    expect(screen.getByText(/Изменение не затрагивает уже созданные аукционы/)).toBeInTheDocument();
  });

  it('a valid amount is saved', async () => {
    fireEvent.change(await depositField(), { target: { value: '15000' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1));
    expect(apiPut.mock.calls[0][1]).toMatchObject({ auction_participation_deposit_amount: '15000' });
  });

  it.each([
    ['0', 'Должно быть больше 0'],
    ['-5', 'Должно быть больше 0'],
    ['1.234', 'Не больше 2 знаков после запятой'],
    ['100000000', 'Максимум: 99999999.99'],
  ])('%s is refused on the page (%s) and nothing is sent', async (value, error) => {
    fireEvent.change(await depositField(), { target: { value } });
    fireEvent.click(screen.getByText('Сохранить настройки'));
    expect(await screen.findByText(error)).toBeInTheDocument();
    expect(apiPut).not.toHaveBeenCalled();
  });

  it('a server refusal is shown', async () => {
    apiPut.mockRejectedValue(Object.assign(new Error('400'), { isAxiosError: true, response: { status: 400,
      data: { message: 'Депозит для участия: не больше двух знаков после запятой' } } }));
    fireEvent.change(await depositField(), { target: { value: '15000' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));
    expect(await screen.findByText(/не больше двух знаков после запятой/)).toBeInTheDocument();
  });
});
