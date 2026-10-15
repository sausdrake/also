import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
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
