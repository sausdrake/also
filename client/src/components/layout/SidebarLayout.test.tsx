import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { createMemoryRouter, NavLink, RouterProvider } from 'react-router-dom';
import { SidebarLayout } from './SidebarLayout';
import { DiagnosticDashboard } from '../../pages/DiagnosticDashboard';
import api from '../../api/client';

/**
 * FE-MOBILE-01: the drawer behaviour of the role-area layout (the lg breakpoint itself is CSS and is
 * verified in the browser at 375/390/768/1440).
 */

vi.mock('../../api/client', () => ({
  default: { get: vi.fn(() => Promise.resolve({ data: [] })), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  BASE_URL: 'http://api.test',
}));

function renderLayout() {
  const Nav = ({ onNavigate }: { onNavigate: () => void }) => (
    <nav aria-label="Разделы">
      <NavLink to="/a" onClick={onNavigate}>Раздел А</NavLink>
      <NavLink to="/b" onClick={onNavigate}>Раздел Б</NavLink>
    </nav>
  );
  const page = (text: string) => (
    <SidebarLayout title="Тестовая зона" sidebar={onNavigate => <Nav onNavigate={onNavigate} />}><p>{text}</p></SidebarLayout>
  );
  render(<RouterProvider router={createMemoryRouter([
    { path: '/a', element: page('PAGE A') }, { path: '/b', element: page('PAGE B') },
  ], { initialEntries: ['/a'] })} />);
}

const menuButton = () => screen.getByRole('button', { name: 'Открыть меню' });
const drawer = () => screen.queryByRole('dialog', { name: 'Меню: Тестовая зона' });

describe('FE-MOBILE-01: SidebarLayout drawer', () => {
  it('the top bar names the area; the drawer is closed until the menu button is pressed', () => {
    renderLayout();
    expect(screen.getByText('Тестовая зона')).toBeInTheDocument();
    expect(menuButton()).toHaveAttribute('aria-expanded', 'false');
    expect(drawer()).not.toBeInTheDocument();
    fireEvent.click(menuButton());
    expect(drawer()).toBeInTheDocument();
    expect(menuButton()).toHaveAttribute('aria-expanded', 'true');
  });

  it('navigating from the drawer closes it and shows the page', async () => {
    renderLayout();
    fireEvent.click(menuButton());
    const links = screen.getAllByText('Раздел Б');           // static column + drawer copy
    fireEvent.click(links[links.length - 1]);
    expect(await screen.findByText('PAGE B')).toBeInTheDocument();
    expect(drawer()).not.toBeInTheDocument();
  });

  it.each([
    ['Escape', () => fireEvent.keyDown(document, { key: 'Escape' })],
    ['the backdrop', () => fireEvent.click(screen.getByTestId('drawer-backdrop'))],
    ['the close button', () => fireEvent.click(screen.getByRole('button', { name: 'Закрыть меню' }))],
  ])('%s closes the drawer', (_n, closeIt) => {
    renderLayout();
    fireEvent.click(menuButton());
    closeIt();
    expect(drawer()).not.toBeInTheDocument();
  });

  it('another key does not close it', () => {
    renderLayout();
    fireEvent.click(menuButton());
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(drawer()).toBeInTheDocument();
  });
});

describe('FE-MOBILE-01: diagnostician area uses the drawer', () => {
  it('the menu reaches "Мои отчёты" and closes', async () => {
    (api.get as unknown as ReturnType<typeof vi.fn>).mockImplementation((url: string) =>
      Promise.resolve({ data: url.startsWith('/diagnostic/work/my') ? { content: [] } : [] }));
    render(<RouterProvider router={createMemoryRouter([{ path: '/diagnostic/*', element: <DiagnosticDashboard /> }],
      { initialEntries: ['/diagnostic/dashboard'] })} />);
    fireEvent.click(screen.getByRole('button', { name: 'Открыть меню' }));
    const dialog = screen.getByRole('dialog', { name: 'Меню: Диагностика' });
    fireEvent.click(dialog.querySelector('a[href="/diagnostic/reports"]')!);
    expect(await screen.findByRole('heading', { name: 'Мои отчёты' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog', { name: 'Меню: Диагностика' })).not.toBeInTheDocument();
  });
});
