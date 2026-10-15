import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/** Scenarios 5.3 / 17.2: an auction cannot be created without its main photo. */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url.startsWith('/admin/auctions?')) return Promise.resolve({ data: { content: [] } });
    // the create form is only offered when an approved vehicle exists
    if (url === '/admin/vehicles/approved') return Promise.resolve({ data: [{ id: 9, brand: 'BMW', model: 'X5', year: 2020, vin: 'WBA00000000000001' }] });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AdminDashboard AuctionsPage - main photo is mandatory', () => {
  it('creating without any photo shows the error and sends nothing', async () => {
    const router = createMemoryRouter(
      [{ path: '/admin/*', element: <AdminDashboard /> }],
      { initialEntries: ['/admin/auctions'] }
    );
    render(<RouterProvider router={router} />);

    fireEvent.click(await screen.findByText('+ Создать аукцион'));
    fireEvent.click(screen.getByText('Создать'));

    expect(screen.getByText('Загрузите главное фото (обязательно)')).toBeInTheDocument();
    expect(apiPost).not.toHaveBeenCalled();
  });
});
