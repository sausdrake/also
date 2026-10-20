import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * The diagnostic fee is fail-closed on the server (no approved default) and has to be configured
 * by the admin before any diagnostic request can be created (scenario 1). The settings page must
 * therefore offer it even when it has never been set, and must never send empty values.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPut = api.put as unknown as ReturnType<typeof vi.fn>;

const stored = {
  deposit_percent: '1',
  default_bid_step_percent: '1',
  diagnostic_deposit_amount: '10000',
  auction_confirm_hours: '24',
  completed_auction_visibility_hours: '24',
};

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) =>
    Promise.resolve({ data: url === '/admin/settings' ? { ...stored } : [] }));
  apiPut.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function renderSettings() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/settings'] }
  );
  render(<RouterProvider router={router} />);
  return screen.findByLabelText(/Стоимость диагностики \(сом\)/);
}

describe('AdminDashboard SettingsPage - diagnostic fee', () => {
  it('shows the never-configured diagnostic fee as an empty, flagged field', async () => {
    const fee = await renderSettings();

    expect(fee).toHaveValue(null);
    expect(screen.getAllByText('Не задано')).toHaveLength(1);
    expect(screen.getByLabelText(/Залог для диагностики/)).toHaveValue(10000);
  });

  it('saves the fee once entered, together with the stored settings', async () => {
    fireEvent.change(await renderSettings(), { target: { value: '4000' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));

    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1));
    // FE-SETTINGS-01: only the settings the backend reads are offered and sent; the dead rows
    // (deposit_percent, default_bid_step_percent, completed_auction_visibility_hours) stay untouched.
    expect(apiPut).toHaveBeenCalledWith('/admin/settings',
      { diagnostic_deposit_amount: '10000', auction_confirm_hours: '24', diagnostic_fee_amount: '4000' });
    expect(await screen.findByText('Настройки сохранены')).toBeInTheDocument();
  });

  it('never sends an unset (empty) value - also after a value was typed and cleared', async () => {
    const fee = await renderSettings();
    fireEvent.change(fee, { target: { value: '4000' } });
    fireEvent.change(fee, { target: { value: '' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));

    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1));
    expect(apiPut.mock.calls[0][1]).not.toHaveProperty('diagnostic_fee_amount');
  });

  it('a negative fee is rejected before saving', async () => {
    fireEvent.change(await renderSettings(), { target: { value: '-5' } });
    fireEvent.click(screen.getByText('Сохранить настройки'));

    expect(await screen.findByText('Минимум: 0')).toBeInTheDocument();
    expect(apiPut).not.toHaveBeenCalled();
  });
});
