import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * FE-SETTINGS-01: only settings with a backend reader are offered (diagnostic_deposit_amount,
 * diagnostic_fee_amount, auction_confirm_hours). The dead rows stay in the database but are neither
 * shown nor sent. A failed or malformed load is an error with a retry - never a form of "Не задано".
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPut = api.put as unknown as ReturnType<typeof vi.fn>;
const httpError = (status: number, data: unknown) => Object.assign(new Error(`HTTP ${status}`), { isAxiosError: true, response: { status, data } });

const STORED = {
  deposit_percent: '1', default_bid_step_percent: '1', commission_percent: '5', platform_fee_percent: '5',
  completed_auction_visibility_hours: '720', diagnostic_deposit_amount: '10000', diagnostic_fee_amount: '3000', auction_confirm_hours: '24',
};

function mockSettings(...answers: unknown[]) {
  let i = 0;
  apiGet.mockImplementation((url: string) => {
    if (url !== '/admin/settings') return Promise.resolve({ data: [] });
    const a = answers[Math.min(i++, answers.length - 1)];
    return a instanceof Error ? Promise.reject(a) : Promise.resolve({ data: a });
  });
}

function renderSettings() {
  render(<RouterProvider router={createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/settings'] })} />);
}

beforeEach(() => {
  apiGet.mockReset();
  apiPut.mockReset();
});

describe('FE-SETTINGS-01: only live settings', () => {
  it('shows exactly the four settings the backend reads (DEPOSIT-01 added the participation deposit)', async () => {
    mockSettings({ ...STORED });
    renderSettings();
    expect(await screen.findByLabelText(/Стоимость диагностики/)).toHaveValue(3000);
    expect(screen.getByLabelText(/Залог для диагностики/)).toHaveValue(10000);
    expect(screen.getByLabelText(/Подтверждение аукциона/)).toHaveValue(24);
    expect(screen.getByLabelText(/Депозит для участия в аукционах/)).toBeInTheDocument();
    expect(screen.getAllByRole('spinbutton')).toHaveLength(4);
    for (const dead of [/Депозит для аукциона/, /Минимальный шаг ставки/, /Видимость завершённых/, /Комиссия/]) {
      expect(screen.queryByText(dead)).not.toBeInTheDocument();
    }
  });

  it('save sends only the live keys even though the dead rows came back from GET', async () => {
    mockSettings({ ...STORED });
    apiPut.mockResolvedValue({ data: {} });
    renderSettings();
    fireEvent.change(await screen.findByLabelText(/Подтверждение аукциона/), { target: { value: '12' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1));
    expect(Object.keys(apiPut.mock.calls[0][1]).sort()).toEqual(['auction_confirm_hours', 'diagnostic_deposit_amount', 'diagnostic_fee_amount']);
    expect(apiPut.mock.calls[0][1].auction_confirm_hours).toBe('12');
  });
});

describe('FE-SETTINGS-01 adversarial: load and save failures', () => {
  it.each([
    ['500', httpError(500, { message: 'Произошла непредвиденная ошибка' }), 'Произошла непредвиденная ошибка'],
    ['403', httpError(403, { error: 'Forbidden', message: 'Access is denied' }), 'Недостаточно прав для этого действия.'],
    ['malformed (array)', [], 'Не удалось загрузить настройки'],
    ['malformed (null)', null, 'Не удалось загрузить настройки'],
  ])('%s -> error with retry, no "Не задано" form and no save button', async (_n, answer, text) => {
    mockSettings(answer, { ...STORED });
    renderSettings();
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(text);
    expect(screen.queryByText('Не задано')).not.toBeInTheDocument();
    expect(screen.queryByText('Сохранить настройки')).not.toBeInTheDocument();
    fireEvent.click(within(alert).getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByLabelText(/Стоимость диагностики/)).toHaveValue(3000);
  });

  it('a refused save shows the backend reason', async () => {
    mockSettings({ ...STORED });
    apiPut.mockRejectedValue(httpError(400, { error: 'Некорректный запрос', message: "Недопустимый ключ настройки: 'x'" }));
    renderSettings();
    await screen.findByLabelText(/Стоимость диагностики/);
    fireEvent.click(screen.getByText('Сохранить настройки'));
    expect(await screen.findByText("Недопустимый ключ настройки: 'x'")).toBeInTheDocument();
  });
});
