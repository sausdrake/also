import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/** BL-24: the auction form must not say "Нет доступных автомобилей" when the list failed to load. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => { apiGet.mockReset(); });
afterEach(() => { vi.restoreAllMocks(); });

describe('BL-24: approved vehicles in the auction form', () => {
  it('a failed load is an error with a retry, not "no vehicles"; the retry fills the select', async () => {
    let healed = false;
    apiGet.mockImplementation((url: string) => {
      if (url.startsWith('/admin/vehicles/approved')) {
        return healed
          ? Promise.resolve({ data: [{ id: 3, brand: 'Toyota', model: 'Camry', year: 2019, vin: 'JTNB11HK0K3000001' }] })
          : Promise.reject(Object.assign(new Error('Network Error'), { isAxiosError: true }));
      }
      if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [] } });
      return Promise.resolve({ data: [] });
    });
    const router = createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: ['/admin/auctions'] });
    render(<RouterProvider router={router} />);
    fireEvent.click(await screen.findByText('+ Создать аукцион'));

    expect(await screen.findByRole('alert')).toHaveTextContent('Нет связи с сервером');
    expect(screen.queryByText('Нет доступных автомобилей')).not.toBeInTheDocument();

    healed = true;
    fireEvent.click(screen.getByRole('button', { name: 'Повторить' }));
    expect(await screen.findByText(/Toyota Camry/)).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
