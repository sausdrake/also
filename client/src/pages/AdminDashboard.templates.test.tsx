import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * Scenario 14.4: "Копировать" opens the "Новое название шаблона" modal; saving creates the copy
 * through POST /admin/diagnostic/templates/{id}/copy and opens it in the constructor. Rendered
 * through AdminDashboard's own routing.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;

const sedan = { id: 1, name: 'Седан', vehicleType: 'SEDAN', isActive: true, groups: [] };
const copy = { id: 2, name: 'Внедорожник', vehicleType: 'SEDAN', isActive: false, groups: [] };

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/admin/diagnostic/templates') return Promise.resolve({ data: { content: [sedan] } });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: copy });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function renderTemplatesPage() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/diagnostic/templates'] }
  );
  render(<RouterProvider router={router} />);
  return screen.findByLabelText('Копировать шаблон Седан');
}

describe('AdminDashboard TemplatesPage - copy template (scenario 14.4)', () => {
  it('copies the template under the new name and opens the copy', async () => {
    fireEvent.click(await renderTemplatesPage());

    expect(screen.getByText('Новое название шаблона')).toBeInTheDocument();
    const save = screen.getByText('Сохранить копию');
    expect(save).toBeDisabled();

    fireEvent.change(screen.getByLabelText('Название нового шаблона'), { target: { value: '  Внедорожник ' } });
    fireEvent.click(save);

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/diagnostic/templates/1/copy', { name: 'Внедорожник' }));
    expect(await screen.findByText(/скопирован как «Внедорожник» \(неактивен\)/)).toBeInTheDocument();
    expect(screen.queryByText('Новое название шаблона')).not.toBeInTheDocument();
    // the copy is opened in the constructor
    expect(screen.getAllByText('Внедорожник').length).toBeGreaterThan(0);
  });

  it('cancel closes the modal without copying', async () => {
    fireEvent.click(await renderTemplatesPage());
    const modal = screen.getByText('Новое название шаблона').parentElement as HTMLElement;

    fireEvent.click(within(modal).getByText('Отмена'));

    expect(screen.queryByText('Новое название шаблона')).not.toBeInTheDocument();
    expect(apiPost).not.toHaveBeenCalled();
  });

  it('a failed copy keeps the modal open and shows the server message', async () => {
    apiPost.mockRejectedValueOnce({ response: { data: { message: 'Шаблон не найден: 1' } } });
    fireEvent.click(await renderTemplatesPage());
    fireEvent.change(screen.getByLabelText('Название нового шаблона'), { target: { value: 'Внедорожник' } });

    fireEvent.click(screen.getByText('Сохранить копию'));

    expect(await screen.findByText('Шаблон не найден: 1')).toBeInTheDocument();
    expect(screen.getByText('Новое название шаблона')).toBeInTheDocument();
  });
});
