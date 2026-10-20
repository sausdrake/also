import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';

/**
 * FE-MOBILE-02: the admin area uses the shared SidebarLayout - below lg the 240px sidebar is a
 * drawer. Every navigation link closes it; expanding the "Диагностика" group does not (it is not a
 * navigation). The breakpoint itself is CSS and is verified in the browser.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: [] })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

function renderAdmin(path = '/admin') {
  render(<RouterProvider router={createMemoryRouter([{ path: '/admin/*', element: <AdminDashboard /> }], { initialEntries: [path] })} />);
}

const openMenu = () => {
  fireEvent.click(screen.getByRole('button', { name: 'Открыть меню' }));
  return screen.getByRole('dialog', { name: 'Меню: Администратор' });
};

describe('FE-MOBILE-02: admin drawer', () => {
  it('a top bar names the area', () => {
    renderAdmin();
    expect(screen.getByRole('button', { name: 'Открыть меню' })).toHaveAttribute('aria-expanded', 'false');
  });

  it.each([
    ['/admin/withdrawals', 'Заявки на вывод средств'],
    ['/admin/settings', 'Настройки системы'],
    ['/admin/users', 'Пользователи'],
  ])('the drawer link to %s navigates and closes the drawer', async (href, heading) => {
    renderAdmin();
    fireEvent.click(openMenu().querySelector(`a[href="${href}"]`)!);
    expect(await screen.findByRole('heading', { name: heading })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Меню: Администратор' })).not.toBeInTheDocument();
  });

  it('expanding "Диагностика" keeps the drawer open; its sub-link closes it', async () => {
    renderAdmin();
    const drawer = openMenu();
    fireEvent.click(within(drawer).getByRole('button', { name: /Диагностика/ }));
    expect(screen.getByRole('dialog', { name: 'Меню: Администратор' })).toBeInTheDocument();
    fireEvent.click(drawer.querySelector('a[href="/admin/diagnostic/reports"]')!);
    expect(screen.queryByRole('dialog', { name: 'Меню: Администратор' })).not.toBeInTheDocument();
  });
});
