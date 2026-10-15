import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router-dom';
import { AdminDashboard } from './AdminDashboard';
import api from '../api/client';

/**
 * Scenario 13 (Drag&Drop order): groups and items are reordered by dragging (or ↑/↓) and the new
 * order is saved to sort_order. Legacy rows that all share one sort_order are renumbered 1..n.
 */

vi.mock('../api/client', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const apiGet = api.get as unknown as ReturnType<typeof vi.fn>;
const apiPost = api.post as unknown as ReturnType<typeof vi.fn>;
const apiPut = api.put as unknown as ReturnType<typeof vi.fn>;

const item = (id: number, title: string, sortOrder: number) => ({
  id, title, description: `${title} desc`, fieldType: 'TEXT', isRequired: true,
  hasPhoto: false, hasVideo: id === 102, hasRating: false, sortOrder, // legacy, non-sequential values
});
const template = {
  id: 1, name: 'Седан', vehicleType: 'SEDAN', isActive: true,
  groups: [
    { id: 10, name: 'Кузов', sortOrder: 1, items: [item(101, 'Царапины', 1), item(102, 'Вмятины', 5)] },
    { id: 11, name: 'Двигатель', sortOrder: 2, items: [] },
  ],
};

beforeEach(() => {
  apiGet.mockReset().mockImplementation((url: string) => {
    if (url === '/admin/diagnostic/templates') return Promise.resolve({ data: { content: [template] } });
    if (url === '/admin/diagnostic/templates/1') return Promise.resolve({ data: template });
    return Promise.resolve({ data: [] });
  });
  apiPut.mockReset().mockResolvedValue({ data: {} });
  apiPost.mockReset().mockResolvedValue({ data: {} });
});

afterEach(() => {
  vi.restoreAllMocks();
});

async function openConstructor() {
  const router = createMemoryRouter(
    [{ path: '/admin/*', element: <AdminDashboard /> }],
    { initialEntries: ['/admin/diagnostic/templates'] }
  );
  render(<RouterProvider router={router} />);
  fireEvent.click(await screen.findByText('Седан'));
  await screen.findByText('Вмятины');
}

const putsTo = (prefix: string) => apiPut.mock.calls.filter(c => String(c[0]).startsWith(prefix));

describe('AdminDashboard TemplatesPage - group and item order (scenario 13)', () => {
  it('↓ moves a group down and saves both new positions', async () => {
    await openConstructor();
    fireEvent.click(screen.getByLabelText('Группа Кузов ниже'));

    await waitFor(() => expect(putsTo('/admin/diagnostic/groups/')).toHaveLength(2));
    expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/groups/11', { name: 'Двигатель', sortOrder: 1 });
    expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/groups/10', { name: 'Кузов', sortOrder: 2 });
  });

  it('dragging a group onto another moves it there', async () => {
    await openConstructor();
    fireEvent.dragStart(screen.getByTestId('group-11'));
    fireEvent.dragOver(screen.getByTestId('group-10'));
    fireEvent.drop(screen.getByTestId('group-10'));

    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/groups/11', { name: 'Двигатель', sortOrder: 1 }));
    expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/groups/10', { name: 'Кузов', sortOrder: 2 });
  });

  it('dragging an item renumbers its group, keeping every other attribute', async () => {
    await openConstructor();
    fireEvent.dragStart(screen.getByTestId('item-101'));
    fireEvent.dragOver(screen.getByTestId('item-102'));
    fireEvent.drop(screen.getByTestId('item-102'));

    // [Вмятины, Царапины] renumbered 1..2 - both rows change, every attribute is kept
    await waitFor(() => expect(putsTo('/admin/diagnostic/items/')).toHaveLength(2));
    expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/items/102', {
      title: 'Вмятины', description: 'Вмятины desc', fieldType: 'TEXT', isRequired: true,
      hasPhoto: false, hasVideo: true, hasRating: false, sortOrder: 1,
    });
    expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/items/101', {
      title: 'Царапины', description: 'Царапины desc', fieldType: 'TEXT', isRequired: true,
      hasPhoto: false, hasVideo: false, hasRating: false, sortOrder: 2,
    });
    expect(putsTo('/admin/diagnostic/groups/')).toHaveLength(0);
  });

  it('↑ on an item moves it up; the first item and first group cannot move up', async () => {
    await openConstructor();
    expect(screen.getByLabelText('Пункт Царапины выше')).toBeDisabled();
    expect(screen.getByLabelText('Группа Кузов выше')).toBeDisabled();
    expect(screen.getByLabelText('Группа Двигатель ниже')).toBeDisabled();

    fireEvent.click(screen.getByLabelText('Пункт Вмятины выше'));
    await waitFor(() => expect(apiPut).toHaveBeenCalledWith('/admin/diagnostic/items/101', expect.objectContaining({ sortOrder: 2 })));
  });

  it('a new item is added at the end of its group', async () => {
    await openConstructor();
    fireEvent.click(screen.getAllByText('+ Поле')[0]);
    fireEvent.change(screen.getByPlaceholderText('Название поля *'), { target: { value: 'Коррозия' } });
    fireEvent.click(screen.getByText('Добавить поле'));

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith('/admin/diagnostic/groups/10/items',
      expect.objectContaining({ title: 'Коррозия', sortOrder: 3 })));
  });
});
