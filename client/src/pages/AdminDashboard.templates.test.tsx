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
const apiPut = api.put as unknown as ReturnType<typeof vi.fn>;
const apiDelete = api.delete as unknown as ReturnType<typeof vi.fn>;

const sedan = { id: 1, name: 'Седан', vehicleType: 'SEDAN', isActive: true, groups: [] };
const copy = { id: 2, name: 'Внедорожник', vehicleType: 'SEDAN', isActive: false, groups: [] };
const engineWork = {
  id: 100, title: 'Работа двигателя', description: 'Холодный пуск', fieldType: 'TEXT', isRequired: false,
  hasPhoto: true, hasVideo: true, hasRating: false, sortOrder: 3,
};
const fullSedan = {
  ...sedan,
  groups: [
    { id: 10, name: 'Двигатель', sortOrder: 1, items: [engineWork] },
    { id: 11, name: 'Ходовая часть', sortOrder: 2, items: [] },
  ],
};

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/admin/diagnostic/templates') return Promise.resolve({ data: { content: [sedan] } });
    return Promise.resolve({ data: [] });
  });
  apiPost.mockReset().mockResolvedValue({ data: copy });
  apiPut.mockReset().mockResolvedValue({ data: {} });
  apiDelete.mockReset().mockResolvedValue({ data: {} });
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

describe('AdminDashboard TemplatesPage - edit item and delete group (scenario 14.2-14.3)', () => {
  async function openSedanConstructor() {
    apiGet.mockImplementation((url: string) => {
      if (url === '/admin/diagnostic/templates') return Promise.resolve({ data: { content: [sedan] } });
      if (url === '/admin/diagnostic/templates/1') return Promise.resolve({ data: fullSedan });
      return Promise.resolve({ data: [] });
    });
    await renderTemplatesPage();
    fireEvent.click(screen.getByText('Седан'));
    return screen.findByLabelText('Редактировать пункт Работа двигателя');
  }

  it('14.2: ✏️ opens the edit modal; making the item required saves it with everything else unchanged', async () => {
    fireEvent.click(await openSedanConstructor());

    expect(screen.getByText('Редактировать пункт')).toBeInTheDocument();
    expect(screen.getByLabelText('Название пункта')).toHaveValue('Работа двигателя');
    const required = screen.getByLabelText('Обязательный пункт');
    expect(required).not.toBeChecked();

    fireEvent.click(required);
    fireEvent.click(screen.getByText('Сохранить'));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/items/100', {
      title: 'Работа двигателя', description: 'Холодный пуск', fieldType: 'TEXT', isRequired: true,
      hasPhoto: true, hasVideo: true, hasRating: false, sortOrder: 3,
    }));
    expect(await screen.findByText('Пункт сохранён')).toBeInTheDocument();
    expect(screen.queryByText('Редактировать пункт')).not.toBeInTheDocument();
  });

  it('14.3: deleting a group asks for confirmation; declining keeps it', async () => {
    await openSedanConstructor();
    const deleteButtons = screen.getAllByText('Удалить');

    fireEvent.click(deleteButtons[1]);
    expect(screen.getByText('Удалить группу и все пункты внутри?')).toBeInTheDocument();
    expect(apiDelete).not.toHaveBeenCalled();

    const confirm = screen.getByText('Удалить группу и все пункты внутри?').parentElement as HTMLElement;
    fireEvent.click(within(confirm).getByText('Отмена'));
    expect(screen.queryByText('Удалить группу и все пункты внутри?')).not.toBeInTheDocument();
    expect(apiDelete).not.toHaveBeenCalled();
  });

  it('14.3: confirming deletes exactly that group', async () => {
    await openSedanConstructor();

    fireEvent.click(screen.getAllByText('Удалить')[1]);
    const confirm = screen.getByText('Удалить группу и все пункты внутри?').parentElement as HTMLElement;
    fireEvent.click(within(confirm).getByText('Подтвердить'));

    await waitFor(() => expect(apiDelete).toHaveBeenCalledWith('/admin/diagnostic/groups/11'));
    expect(apiDelete).toHaveBeenCalledTimes(1);
  });
});
